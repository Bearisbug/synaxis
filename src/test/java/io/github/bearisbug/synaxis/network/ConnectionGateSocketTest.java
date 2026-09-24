package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.network.packet.server.status.ResponsePacket;
import io.github.bearisbug.synaxis.network.socket.Server;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection gate ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-001}, {@code API-PROD-003})
 * through a real server on loopback: what a peer sees when each layer turns it away, and that
 * nothing of the login runs for a connection turned away.
 */
public class ConnectionGateSocketTest {

    /** Long enough for a connection that is expected to stay open to stay open during a test. */
    private static final long LONG_HANDSHAKE_TIMEOUT = 60_000;

    /** L0: the socket past the process limit is closed at accept, and those before it are served. */
    @Test
    public void theSocketPastTheProcessLimitIsClosedWithoutAByte() throws Exception {
        try (Rig rig = Rig.start(base().maxConnections(3))) {
            final Peer first = rig.connect();
            final Peer second = rig.connect();
            final Peer third = rig.connect();
            awaitUntil(() -> rig.gate().connections() == 3, "the three sockets are accepted");

            final Peer fourth = rig.connect();
            assertTrue(fourth.closedWithoutAByte(), "the fourth socket is over synaxis.max-connections");
            assertEquals(1, rig.gate().rejected(ConnectionGate.Reason.CLOSED_AT_ACCEPT));

            for (Peer peer : List.of(first, second, third)) {
                peer.handshake(Intent.STATUS);
                peer.send(ConnectionState.STATUS, new StatusRequestPacket());
                assertInstanceOf(ResponsePacket.class, peer.read(ConnectionState.STATUS),
                        "the sockets within the limit are served");
            }
        }
    }

    /** L1 without the PROXY protocol: the connection past the address limit is closed at accept. */
    @Test
    public void withoutTheProxyProtocolTheAddressLimitIsCheckedAtAccept() throws Exception {
        try (Rig rig = Rig.start(base().maxConnectionsPerIp(2))) {
            rig.connect();
            rig.connect();
            awaitUntil(() -> rig.gate().connections() == 2, "the two sockets are accepted");

            assertTrue(rig.connect().closedWithoutAByte(), "the third connection of 127.0.0.1");
            assertEquals(1, rig.gate().rejected(ConnectionGate.Reason.TOO_MANY));
            assertEquals(2, rig.gate().connections());
        }
    }

    /**
     * L1 with the PROXY protocol: the real address is only known once the header is read, so the
     * limit is checked at the handshake, and a login is told why it is turned away.
     */
    @Test
    public void withTheProxyProtocolTheAddressLimitIsCheckedAtTheHandshake() throws Exception {
        final AtomicInteger logins = new AtomicInteger();
        try (Rig rig = Rig.start(proxied().maxConnectionsPerIp(2))) {
            rig.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, _ -> logins.incrementAndGet());
            for (int i = 0; i < 2; i++) {
                final Peer peer = rig.connect();
                peer.proxyHeader("203.0.113.7");
                peer.handshake(Intent.LOGIN);
                peer.send(ConnectionState.LOGIN, new ClientLoginStartPacket("holder" + i, UUID.randomUUID()));
                assertTrue(peer.loggedIn(), "connection " + (i + 1) + " of 203.0.113.7");
            }

            final Peer login = rig.connect();
            login.proxyHeader("203.0.113.7");
            login.handshake(Intent.LOGIN);
            login.send(ConnectionState.LOGIN, new ClientLoginStartPacket("third", UUID.randomUUID()));
            assertEquals("synaxis:too-many-connections", login.disconnectReason());
            assertTrue(login.closedWithoutAByte(), "and closed");

            final Peer status = rig.connect();
            status.proxyHeader("203.0.113.7");
            status.handshake(Intent.STATUS);
            assertTrue(status.closedWithoutAByte(), "a status handshake is closed without an answer");

            final Peer other = rig.connect();
            other.proxyHeader("203.0.113.8");
            other.handshake(Intent.LOGIN);
            other.send(ConnectionState.LOGIN, new ClientLoginStartPacket("other", UUID.randomUUID()));
            assertTrue(other.loggedIn(), "another address behind the same proxy");

            assertEquals(2, rig.gate().rejected(ConnectionGate.Reason.TOO_MANY));
            assertEquals(0, rig.gate().rejected(ConnectionGate.Reason.CLOSED_AT_ACCEPT));
            assertEquals(3, logins.get(), "the login turned away never reached the login event");
        }
    }

    /**
     * An idle connection holds a socket until {@code synaxis.handshake-timeout-ms}, not the 30 s of
     * the login timeout. With the PROXY protocol that is the only bound on an address that opens
     * connections and says nothing.
     */
    @Test
    public void aConnectionThatSaysNothingIsClosedAtTheHandshakeTimeout() throws Exception {
        try (Rig rig = Rig.start(proxied().handshakeTimeout(500).maxConnectionsPerIp(2))) {
            final List<Peer> idle = new ArrayList<>();
            for (int i = 0; i < 5; i++) idle.add(rig.connect());
            final Peer talking = rig.connect();
            talking.proxyHeader("203.0.113.9");
            talking.handshake(Intent.STATUS);
            final long start = System.nanoTime();

            for (Peer peer : idle) assertTrue(peer.closedWithoutAByte(), "an idle connection is closed");
            final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 400 && elapsed < 5_000, "closed at the handshake timeout, after " + elapsed + " ms");
            assertEquals(5, rig.gate().rejected(ConnectionGate.Reason.HANDSHAKE_TIMEOUT));
            assertEquals(0, rig.gate().rejected(ConnectionGate.Reason.TOO_MANY),
                    "with the PROXY protocol nothing is closed at accept");

            Thread.sleep(600);
            talking.send(ConnectionState.STATUS, new StatusRequestPacket());
            assertInstanceOf(ResponsePacket.class, talking.read(ConnectionState.STATUS),
                    "a connection that sent its handshake in time is not closed by it");
        }
    }

    /** L2: the fourth login of an address in a row is throttled, the bucket refills in 2 s. */
    @Test
    public void theFourthLoginOfAnAddressIsThrottledAndLetInTwoSecondsLater() throws Exception {
        try (Rig rig = Rig.start(base())) {
            for (int i = 0; i < 3; i++) assertTrue(rig.login("player" + i).loggedIn(), "login " + (i + 1));
            final Peer fourth = rig.login("player3");
            assertEquals("synaxis:login-throttled", fourth.disconnectReason());

            rig.advance(TimeUnit.SECONDS.toNanos(2));
            assertTrue(rig.login("player4").loggedIn(), "one token is back 2 s later");
            assertEquals(1, rig.gate().rejected(ConnectionGate.Reason.THROTTLED));
        }
    }

    /** L2: status and transfer have buckets of their own. */
    @Test
    public void statusAndTransferHandshakesCountOnTheirOwnBuckets() throws Exception {
        try (Rig rig = Rig.start(base().maxConnectionsPerIp(64).acceptTransfers(true))) {
            // One after the other: each connection has a read thread of its own, so handshakes sent
            // together are not handled in the order they were sent
            for (int i = 0; i < 10; i++) {
                final Peer peer = rig.connect();
                peer.handshake(Intent.STATUS);
                peer.send(ConnectionState.STATUS, new StatusRequestPacket());
                assertInstanceOf(ResponsePacket.class, peer.read(ConnectionState.STATUS), "status " + (i + 1));
            }
            final Peer eleventh = rig.connect();
            eleventh.handshake(Intent.STATUS);
            assertTrue(eleventh.closedWithoutAByte(), "the eleventh status handshake gets no status response");

            for (int i = 0; i < 10; i++) {
                final Peer peer = rig.connect();
                peer.handshake(Intent.TRANSFER);
                peer.send(ConnectionState.LOGIN, new ClientLoginStartPacket("transfer" + i, UUID.randomUUID()));
                assertTrue(peer.loggedIn(), "transfer " + (i + 1));
            }
            final Peer transfer = rig.connect();
            transfer.handshake(Intent.TRANSFER);
            assertEquals("synaxis:login-throttled", transfer.disconnectReason(), "the eleventh transfer");

            assertTrue(rig.login("login").loggedIn(), "the login bucket of the address is still full");
            assertEquals(2, rig.gate().rejected(ConnectionGate.Reason.THROTTLED));
        }
    }

    /**
     * L3: a full process refuses the login start before anything is done for it, and the status
     * response reports the real limit.
     */
    @Test
    public void aFullProcessRefusesTheLoginStartBeforeTheLoginRuns() throws Exception {
        final AtomicInteger logins = new AtomicInteger();
        try (Rig rig = Rig.start(base().maxPlayers(2).loginBurstPerIp(10))) {
            rig.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, _ -> logins.incrementAndGet());
            final Peer first = rig.login("first");
            assertTrue(first.loggedIn());
            assertTrue(rig.login("second").loggedIn());

            final Peer third = rig.login("third");
            assertEquals("synaxis:shard-full", third.disconnectReason());
            assertTrue(third.closedWithoutAByte(), "and closed");
            assertEquals(2, logins.get(), "the login event never ran for the connection turned away");
            assertEquals(2, rig.gate().seats());
            assertEquals(1, rig.gate().rejected(ConnectionGate.Reason.FULL));
            assertTrue(StatusResponse.json(rig.process()).contains("\"max\":2"), StatusResponse.json(rig.process()));

            first.close();
            awaitUntil(() -> rig.gate().seats() == 1, "a connection closing gives its seat back");
            assertTrue(rig.login("fourth").loggedIn(), "the seat that was given back");
        }
    }

    /**
     * L3 counts seats, not players in play: twenty login starts arriving together on a process with
     * five seats let exactly five in.
     */
    @Test
    public void loginStartsArrivingTogetherNeverTakeMoreSeatsThanThereAre() throws Exception {
        try (Rig rig = Rig.start(base().maxPlayers(5).connectionGateTrustedNetworks("127.0.0.0/8"))) {
            final List<Peer> peers = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final Peer peer = rig.connect();
                peer.handshake(Intent.LOGIN);
                peers.add(peer);
            }
            final CountDownLatch go = new CountDownLatch(1);
            final List<Thread> senders = new ArrayList<>();
            for (int i = 0; i < peers.size(); i++) {
                final Peer peer = peers.get(i);
                final String name = "racer" + i;
                senders.add(Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        peer.send(ConnectionState.LOGIN, new ClientLoginStartPacket(name, UUID.randomUUID()));
                    } catch (InterruptedException | IOException e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            go.countDown();
            for (Thread sender : senders) sender.join(10_000);

            int loggedIn = 0, full = 0;
            for (Peer peer : peers) {
                final ServerPacket first = peer.read(ConnectionState.LOGIN);
                if (first instanceof LoginDisconnectPacket disconnect) {
                    assertEquals("synaxis:shard-full", key(disconnect.kickMessage()));
                    full++;
                } else {
                    loggedIn++;
                }
            }
            assertEquals(5, loggedIn);
            assertEquals(15, full);
            assertEquals(5, rig.gate().seats());
        }
    }

    @Test
    public void theStatusResponseReportsTheSocketLimitWhenSeatsAreUnlimited() {
        final ServerProcess process = ServerProcess.of(base().maxConnections(1_500).build());
        assertTrue(StatusResponse.json(process).contains("\"players\":{\"online\":0,\"max\":1500}"),
                StatusResponse.json(process));
        process.stop();
    }

    private static ServerConfig.Builder base() {
        return ServerConfig.builder().insideTest(true).handshakeTimeout(LONG_HANDSHAKE_TIMEOUT);
    }

    /** Behind a load balancer on loopback, the only way the PROXY protocol may be switched on. */
    private static ServerConfig.Builder proxied() {
        return base().proxyProtocol(true).proxyProtocolRequired(true).proxyProtocolTrustedNetworks("127.0.0.0/8");
    }

    private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), what);
    }

    private static String key(Component component) {
        return assertInstanceOf(TranslatableComponent.class, component,
                "a disconnect reason carries its error code as a translate key").key();
    }

    /**
     * A process with its server listening on a free loopback port. Its gate reads the clock of the
     * rig, which only moves when the test says so: however slow the machine, no token comes back
     * between two steps of a test.
     */
    private record Rig(ServerProcess process, AtomicLong clock, List<Peer> peers) implements AutoCloseable {

        static Rig start(ServerConfig.Builder config) throws IOException {
            final ServerProcess process = ServerProcess.of(config.build());
            final AtomicLong clock = new AtomicLong(System.nanoTime());
            process.connection().gate().clock(clock::get);
            final Server server = process.server();
            server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.start();
            return new Rig(process, clock, new ArrayList<>());
        }

        void advance(long nanos) {
            clock.addAndGet(nanos);
        }

        ConnectionGate gate() {
            return process.connection().gate();
        }

        Peer connect() throws IOException {
            final SocketChannel channel = SocketChannel.open(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()));
            channel.socket().setSoTimeout(5_000);
            final Peer peer = new Peer(channel, process);
            peers.add(peer);
            return peer;
        }

        /** A new connection that sends a login handshake and a login start. */
        Peer login(String name) throws IOException {
            final Peer peer = connect();
            peer.handshake(Intent.LOGIN);
            peer.send(ConnectionState.LOGIN, new ClientLoginStartPacket(name, UUID.randomUUID()));
            return peer;
        }

        @Override
        public void close() {
            for (Peer peer : peers) peer.close();
            process.stop();
        }
    }

    /** The client side of one connection, reading with a timeout through the socket adaptor. */
    private record Peer(SocketChannel channel, ServerProcess process) {

        void proxyHeader(String address) throws IOException {
            final String header = "PROXY TCP4 " + address + " 127.0.0.1 40000 25565\r\n";
            channel.write(ByteBuffer.wrap(header.getBytes(StandardCharsets.US_ASCII)));
        }

        void handshake(Intent intent) throws IOException {
            send(ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), intent));
        }

        void send(ConnectionState state, ClientPacket packet) throws IOException {
            final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
            while (!framed.writeChannel(channel)) {
                // A blocking channel writes everything, the loop only documents the contract
            }
        }

        /** The first packet the server answers with, or null if it closed the connection first. */
        @Nullable ServerPacket read(ConnectionState state) throws IOException {
            final InputStream in = channel.socket().getInputStream();
            final ByteArrayOutputStream frame = new ByteArrayOutputStream();
            int length = 0;
            for (int shift = 0; ; shift += 7) {
                final int b = readByte(in);
                if (b < 0) return null;
                frame.write(b);
                length |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
            }
            for (int i = 0; i < length; i++) {
                final int b = readByte(in);
                if (b < 0) return null;
                frame.write(b);
            }
            final byte[] bytes = frame.toByteArray();
            final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries());
            final PacketReading.Result<ServerPacket> result;
            try {
                result = PacketReading.readServers(buffer, state, false);
            } catch (DataFormatException e) {
                throw new AssertionError("not a packet: " + bytes.length + " bytes", e);
            }
            if (!(result instanceof PacketReading.Result.Success<ServerPacket> success))
                throw new AssertionError("not a packet: " + bytes.length + " bytes");
            return success.packets().getFirst().packet();
        }

        private static int readByte(InputStream in) throws IOException {
            try {
                return in.read();
            } catch (SocketTimeoutException e) {
                throw new AssertionError("the server neither answered nor closed the connection", e);
            } catch (IOException _) {
                return -1; // Reset by the server, as good as closed
            }
        }

        boolean closedWithoutAByte() throws IOException {
            return readByte(channel.socket().getInputStream()) < 0;
        }

        /** The translate key of the login disconnect the server answered with. */
        String disconnectReason() throws IOException {
            final ServerPacket packet = read(ConnectionState.LOGIN);
            return key(assertInstanceOf(LoginDisconnectPacket.class, packet, "expected a login disconnect").kickMessage());
        }

        /** Whether the login went through: compression is switched on, or the login succeeds. */
        boolean loggedIn() throws IOException {
            final ServerPacket packet = read(ConnectionState.LOGIN);
            if (packet instanceof LoginDisconnectPacket disconnect)
                throw new AssertionError("turned away with " + key(disconnect.kickMessage()));
            return packet instanceof SetCompressionPacket || packet instanceof LoginSuccessPacket;
        }

        void close() {
            try {
                channel.close();
            } catch (IOException _) {
                // Already gone
            }
        }
    }
}
