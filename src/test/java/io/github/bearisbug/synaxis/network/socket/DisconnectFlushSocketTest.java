package io.github.bearisbug.synaxis.network.socket;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.exception.ExceptionHandler;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A kick writes its disconnect packet behind whatever is already queued for the peer, and the write
 * thread closes the channel once it has written them: the peer reads the reason, not just the end of
 * the stream, however much was queued before it.
 * <p>
 * The write thread is held inside a flush while the packets and the kick are queued, by a
 * {@link CachedPacket} whose supplier waits, so that it finds all of them waiting once it goes on.
 * They add up to more than two buffers of the largest size a pooled buffer can have grown to, so the
 * result does not depend on what other tests left in the pool.
 */
public class DisconnectFlushSocketTest {

    private static final String CHANNEL = "synaxis:noise";
    private static final int NOISE_BYTES = 4_000;
    private static final int NOISE_PACKETS = 3 * JvmFlags.MAX_PACKET_SIZE / NOISE_BYTES + 1;

    @Test
    public void theDisconnectPacketIsWrittenBehindMoreThanOneFlushOfQueuedPackets() throws Exception {
        // No compression, so that the frames the peer reads are the packets as written
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().compressionThreshold(0).build());
        final CompletableFuture<PlayerSocketConnection> accepted = new CompletableFuture<>();
        final CountDownLatch kicked = new CountDownLatch(1);
        // The login waits in the event until the kick, so that nothing it sends gets in between
        process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
            accepted.complete((PlayerSocketConnection) event.getConnection());
            await(kicked);
        });
        process.server().init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.server().start();
        try (SocketChannel peer = SocketChannel.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()))) {
            peer.socket().setSoTimeout(10_000);
            send(peer, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), ClientHandshakePacket.Intent.LOGIN));
            send(peer, ConnectionState.LOGIN, new ClientLoginStartPacket("Kicked", UUID.randomUUID()));
            final PlayerSocketConnection connection = accepted.get(10, TimeUnit.SECONDS);

            final CountDownLatch held = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            connection.sendPacket(new CachedPacket(() -> {
                held.countDown();
                await(release);
                return new LoginPluginRequestPacket(0, CHANNEL, new byte[0]);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS), "the write thread never took the first packet");
            final byte[] noise = new byte[NOISE_BYTES];
            for (int i = 1; i <= NOISE_PACKETS; i++)
                connection.sendPacket(new LoginPluginRequestPacket(i, CHANNEL, noise));
            connection.kick(Component.text("bye"));
            release.countDown();
            kicked.countDown();

            final List<ServerPacket> packets = readUntilClosed(peer, process);
            final long requests = packets.stream().filter(LoginPluginRequestPacket.class::isInstance).count();
            final LoginDisconnectPacket disconnect = assertInstanceOf(LoginDisconnectPacket.class, packets.getLast(),
                    "the peer read " + requests + " of the " + (NOISE_PACKETS + 1)
                            + " packets queued before the kick, then the end of the stream");
            assertEquals(NOISE_PACKETS + 1, requests, "every packet queued before the kick");
            assertEquals(Component.text("bye"), disconnect.kickMessage());
        } finally {
            process.stop();
        }
    }

    /**
     * The same queue behind a kick made on the write thread itself, as a listener of outgoing packets
     * or a write that throws would, to a peer whose receive buffer is a few kilobytes and that reads
     * nothing: the write thread blocks writing what is left, and only the closer thread ends it.
     */
    @Test
    public void aKickOnTheWriteThreadClosesAPeerThatStoppedReadingWithinTheBudget() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().compressionThreshold(0).build());
        final CompletableFuture<PlayerSocketConnection> accepted = new CompletableFuture<>();
        final CountDownLatch kicked = new CountDownLatch(1);
        process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
            accepted.complete((PlayerSocketConnection) event.getConnection());
            await(kicked);
        });
        process.server().init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.server().start();
        try (SocketChannel peer = SocketChannel.open()) {
            peer.setOption(StandardSocketOptions.SO_RCVBUF, 4_096);
            peer.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()));
            send(peer, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), ClientHandshakePacket.Intent.LOGIN));
            send(peer, ConnectionState.LOGIN, new ClientLoginStartPacket("Kicked", UUID.randomUUID()));
            final PlayerSocketConnection connection = accepted.get(10, TimeUnit.SECONDS);

            final CountDownLatch held = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            connection.sendPacket(new CachedPacket(() -> {
                held.countDown();
                await(release);
                connection.kick(Component.text("bye"));
                kicked.countDown();
                return new LoginPluginRequestPacket(0, CHANNEL, new byte[0]);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS), "the write thread never took the first packet");
            final byte[] noise = new byte[NOISE_BYTES];
            for (int i = 1; i <= NOISE_PACKETS; i++)
                connection.sendPacket(new LoginPluginRequestPacket(i, CHANNEL, noise));
            release.countDown();
            assertTrue(kicked.await(10, TimeUnit.SECONDS), "the write thread never kicked");

            assertTrue(connection.writeThread().join(Duration.ofSeconds(5)),
                    "the write thread was still writing to a peer that reads nothing 5 s after it kicked");
            assertFalse(connection.getChannel().isOpen(), "the write thread left the channel open");
        } finally {
            process.stop();
        }
    }

    /**
     * A packet that fails on the write thread, here the supplier of a cached packet, is dropped: it
     * is reported once with the connection it was written for, never built again, and the peer reads
     * why it is disconnected.
     */
    @Test
    public void aPacketThatFailsToBeWrittenIsDroppedAndThePeerToldOfTheInternalError() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().compressionThreshold(0).build());
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        final List<Optional<UUID>> reportedFor = new CopyOnWriteArrayList<>();
        process.exception().setExceptionHandler(new ExceptionHandler() {
            @Override
            public void handleException(Throwable e) {
                handleException(e, null);
            }

            @Override
            public void handleException(Throwable e, @Nullable UUID connectionId) {
                reported.add(e);
                reportedFor.add(Optional.ofNullable(connectionId));
            }
        });
        final CompletableFuture<PlayerSocketConnection> accepted = new CompletableFuture<>();
        final CountDownLatch done = new CountDownLatch(1);
        process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
            accepted.complete((PlayerSocketConnection) event.getConnection());
            await(done);
        });
        process.server().init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.server().start();
        try (SocketChannel peer = SocketChannel.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()))) {
            peer.socket().setSoTimeout(10_000);
            send(peer, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), ClientHandshakePacket.Intent.LOGIN));
            send(peer, ConnectionState.LOGIN, new ClientLoginStartPacket("Failing", UUID.randomUUID()));
            final PlayerSocketConnection connection = accepted.get(10, TimeUnit.SECONDS);

            final AtomicInteger built = new AtomicInteger();
            final IllegalStateException failure = new IllegalStateException("the packet could not be built");
            connection.sendPacket(new CachedPacket(() -> {
                built.incrementAndGet();
                throw failure;
            }));

            final List<ServerPacket> packets = readUntilClosed(peer, process);
            assertFalse(packets.isEmpty(), "the peer read nothing before the end of the stream");
            final LoginDisconnectPacket disconnect = assertInstanceOf(LoginDisconnectPacket.class, packets.getLast(),
                    "the peer read " + packets);
            assertEquals(DisconnectReasons.INTERNAL_ERROR.key(),
                    assertInstanceOf(TranslatableComponent.class, disconnect.kickMessage()).key());
            assertEquals(1, built.get(), "the failed packet is not built again");
            assertTrue(connection.writeThread().join(Duration.ofSeconds(5)), "the write thread ended");
            assertEquals(List.of(failure), reported);
            assertEquals(List.of(Optional.of(connection.connectionId())), reportedFor);
        } finally {
            done.countDown();
            process.stop();
        }
    }

    /**
     * The packets written into the same buffer ahead of one that fails still reach the peer, ahead of the reason it
     * is disconnected. On an encrypted connection they are encrypted already, the cipher has moved past them: dropped,
     * the peer could not decrypt what follows.
     */
    @Test
    public void thePacketsWrittenBeforeOneThatFailsInTheSameFlushReachThePeer() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().compressionThreshold(0).build());
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        process.exception().setExceptionHandler(reported::add);
        final CompletableFuture<PlayerSocketConnection> accepted = new CompletableFuture<>();
        final CountDownLatch done = new CountDownLatch(1);
        process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
            accepted.complete((PlayerSocketConnection) event.getConnection());
            await(done);
        });
        process.server().init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.server().start();
        try (SocketChannel peer = SocketChannel.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()))) {
            peer.socket().setSoTimeout(10_000);
            send(peer, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), ClientHandshakePacket.Intent.LOGIN));
            send(peer, ConnectionState.LOGIN, new ClientLoginStartPacket("Failing", UUID.randomUUID()));
            final PlayerSocketConnection connection = accepted.get(10, TimeUnit.SECONDS);

            // Held inside the flush until the two packets behind it are queued, so that the three share its buffer
            final CountDownLatch held = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            connection.sendPacket(new CachedPacket(() -> {
                held.countDown();
                await(release);
                return new LoginPluginRequestPacket(1, CHANNEL, new byte[0]);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS), "the write thread never took the first packet");
            connection.sendPacket(new LoginPluginRequestPacket(2, CHANNEL, new byte[0]));
            final IllegalStateException failure = new IllegalStateException("the packet could not be built");
            connection.sendPacket(new CachedPacket(() -> {
                throw failure;
            }));
            release.countDown();

            final List<ServerPacket> packets = readUntilClosed(peer, process);
            assertEquals(List.of(1, 2), packets.stream().filter(LoginPluginRequestPacket.class::isInstance)
                    .map(packet -> ((LoginPluginRequestPacket) packet).messageId()).toList(), "the peer read " + packets);
            final LoginDisconnectPacket disconnect = assertInstanceOf(LoginDisconnectPacket.class, packets.getLast(),
                    "the peer read " + packets);
            assertEquals(DisconnectReasons.INTERNAL_ERROR.key(),
                    assertInstanceOf(TranslatableComponent.class, disconnect.kickMessage()).key());
            assertEquals(List.of(failure), reported);
        } finally {
            done.countDown();
            process.stop();
        }
    }

    private static void send(SocketChannel peer, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(peer)) {
            // A blocking channel writes everything
        }
    }

    /** Everything the server writes until it closes the connection, as login packets. */
    private static List<ServerPacket> readUntilClosed(SocketChannel peer, ServerProcess process) throws Exception {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final InputStream in = peer.socket().getInputStream();
        final byte[] chunk = new byte[65_536];
        try {
            int read;
            while ((read = in.read(chunk)) >= 0) received.write(chunk, 0, read);
        } catch (SocketTimeoutException e) {
            fail("the server neither wrote nor closed the connection for 10 s after " + received.size() + " bytes", e);
        } catch (IOException _) {
            // A reset ends the stream as well; what arrived before it is what the peer has
        }
        final byte[] bytes = received.toByteArray();
        final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries());
        final var result = PacketReading.readServers(buffer, ConnectionState.LOGIN, false);
        if (!(result instanceof PacketReading.Result.Success<ServerPacket> success))
            throw new AssertionError("no complete packet in " + bytes.length + " bytes: " + result);
        return success.packets().stream().map(PacketReading.ParsedPacket::packet).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("waited 10 s for the test");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
