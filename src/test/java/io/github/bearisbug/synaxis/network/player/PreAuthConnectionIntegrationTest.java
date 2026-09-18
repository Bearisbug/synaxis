package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything a peer can do before it is authenticated is free for it to repeat as fast as the
 * socket accepts bytes. None of it may cost the server an unbounded amount of memory, threads or
 * log lines, and a connection that never logs in has to be closed.
 */
@EnvTest
public class PreAuthConnectionIntegrationTest {

    /**
     * The watchdog sleeps exactly one login timeout and then asks the connection whether it ran out
     * of time. Wall clock milliseconds are integers, so the answer at that exact point has to be
     * yes: the watchdog checks once and nothing else ever closes a silent connection.
     */
    @Test
    public void aConnectionIsTimedOutOnTheMillisecondTheWatchdogWakesUp(Env env) throws IOException {
        final long loginTimeout = env.process().config().loginTimeout();
        try (SocketPair pair = SocketPair.open(env)) {
            // The connection samples the clock itself, retry until it lands in the sampled millisecond
            long connectedAt;
            PlayerSocketConnection connection;
            int attempts = 0;
            do {
                assertTrue(attempts++ < 1000, "could not build a connection within a single millisecond");
                connectedAt = System.currentTimeMillis();
                connection = pair.connection();
            } while (System.currentTimeMillis() != connectedAt);

            assertFalse(connection.loginTimedOut(connectedAt + loginTimeout - 1),
                    "a connection one millisecond short of the timeout still has time");
            assertTrue(connection.loginTimedOut(connectedAt + loginTimeout),
                    "a connection reaching the timeout must be reported as timed out");
        }
    }

    /**
     * The server moves itself to the configuration state as soon as it writes the login success,
     * which a peer buys with a single login start before going silent. Only the state the peer has
     * to reach by itself can call the watchdog off.
     */
    @Test
    public void aLoginTheClientNeverAcknowledgesStillTimesOut(Env env) throws IOException {
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.LOGIN);
            connection.setServerState(ConnectionState.CONFIGURATION); // the login success has been written
            final long expiry = System.currentTimeMillis() + env.process().config().loginTimeout();

            assertTrue(connection.loginTimedOut(expiry),
                    "a peer that never acknowledges the login must still run out of time");
        }
    }

    /**
     * The watchdog holds the connection and its read buffer for the whole login timeout. A
     * connection that is already gone has to release it instead of sleeping the timeout out.
     */
    @Test
    public void aDisconnectCancelsTheLoginTimeoutWatchdog(Env env) throws Exception {
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            final CountDownLatch cancelled = new CountDownLatch(1);
            final Thread watchdog = Thread.ofVirtual().unstarted(() -> {
                try {
                    Thread.sleep(env.process().config().loginTimeout());
                } catch (InterruptedException _) {
                    cancelled.countDown();
                }
            });
            connection.watchLoginTimeout(watchdog);
            watchdog.start();

            connection.disconnect();

            assertTrue(cancelled.await(5, TimeUnit.SECONDS), "the watchdog must not outlive the connection");
        }
    }

    /**
     * The read buffer grows on what the peer announces as the length of the packet it is sending.
     * Announcing the largest packet the protocol allows is a few bytes of input, the buffer it asks
     * for is two megabytes of off-heap memory that is never given back.
     */
    @Test
    public void anAnnouncedPacketLengthDoesNotGrowTheReadBuffer(Env env) throws IOException {
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.CONFIGURATION); // past the pre-auth packet size limit
            final long pooled = connection.readBufferCapacity();

            // A var-int announcing a 2_097_151 byte packet, followed by 37 bytes of it and nothing else
            final byte[] announced = new byte[40];
            announced[0] = (byte) 0xFF;
            announced[1] = (byte) 0xFF;
            announced[2] = 0x7F;
            pair.peer().write(ByteBuffer.wrap(announced));
            connection.read(PacketVanilla.CLIENT_PACKET_PARSER);

            assertEquals(pooled, connection.readBufferCapacity(),
                    "an announced packet length must not allocate the buffer the peer asked for");

            // The buffer still has to grow once the peer leaves it nothing to write into
            pair.peer().write(ByteBuffer.allocate((int) pooled));
            for (int i = 0; i < 32 && connection.readBufferCapacity() == pooled; i++) {
                connection.read(PacketVanilla.CLIENT_PACKET_PARSER);
            }
            assertTrue(connection.readBufferCapacity() > pooled,
                    "a read buffer the peer actually filled must grow to make progress");
        }
    }

    /**
     * A login start is the first packet of a login and the client only ever sends one. Running the
     * whole login again for every repeat spawns a virtual thread per packet, all of them failing
     * once compression is on.
     */
    @Test
    public void aRepeatedLoginStartIsRejected(Env env) throws IOException {
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.LOGIN);
            final var loginStart = new ClientLoginStartPacket("Notch", UUID.randomUUID());

            env.process().packetListener().processClientPacket(loginStart, connection);
            env.process().packetListener().processClientPacket(loginStart, connection);

            assertFalse(connection.isOnline(), "a second login start must drop the connection");
        }
    }

    /**
     * A cookie response carries a key that is only checked for length while decoding. Handing it
     * over unchecked turns every malformed key into an exception reported to the server owner.
     */
    @Test
    public void aMalformedCookieKeyIsRejected(Env env) throws IOException {
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.LOGIN);
            final var response = new ClientCookieResponsePacket("not a key!", null);

            assertDoesNotThrow(() -> env.process().packetListener().processClientPacket(response, connection),
                    "a malformed cookie key is peer input, not an engine failure");
            assertFalse(connection.isOnline(), "a connection sending a malformed cookie key must be dropped");
        }
    }

    /**
     * One read hands over a whole batch of packets, dispatched one by one. A listener that kicks
     * the connection has to end that batch: a peer fits thousands of frames into a single read
     * buffer, and every frame behind the kick is another failure handled and another line logged
     * for a connection that is already gone.
     */
    @Test
    public void aKickStopsTheRestOfTheBatchFromBeingHandled(Env env) throws IOException {
        final AtomicInteger reported = new AtomicInteger();
        env.process().exception().setExceptionHandler(_ -> reported.incrementAndGet());
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.LOGIN);
            // Login plugin responses answering a request the server never sent, each one four
            // bytes: length 3, packet id 0x02, message id 0x07, no data
            final int frameCount = 1000;
            final ByteBuffer frames = ByteBuffer.allocate(frameCount * 4);
            for (int i = 0; i < frameCount; i++) frames.put(new byte[]{0x03, 0x02, 0x07, 0x00});
            pair.peer().write(frames.flip());

            connection.read(PacketVanilla.CLIENT_PACKET_PARSER);

            assertFalse(connection.isOnline(), "an unexpected login plugin response must drop the connection");
            assertEquals(1, reported.get(), "the packets behind a kick must not be handled");
        } finally {
            env.process().exception().setExceptionHandler(null);
        }
    }

    /**
     * A loopback socket pair, the server side wrapped in a real {@link PlayerSocketConnection}.
     */
    private record SocketPair(ServerSocketChannel server, SocketChannel peer, SocketChannel accepted,
                              SocketAddress remoteAddress, Env env) implements AutoCloseable {

        static SocketPair open(Env env) throws IOException {
            final ServerSocketChannel server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final SocketChannel peer = SocketChannel.open(server.getLocalAddress());
            final SocketChannel accepted = server.accept();
            return new SocketPair(server, peer, accepted, accepted.getRemoteAddress(), env);
        }

        /** The current thread stands in for both socket threads, nothing is parked in these tests. */
        PlayerSocketConnection connection() {
            return new PlayerSocketConnection(env.process(), accepted, remoteAddress,
                    Thread.currentThread(), Thread.currentThread());
        }

        @Override
        public void close() throws IOException {
            peer.close();
            accepted.close();
            server.close();
        }
    }
}
