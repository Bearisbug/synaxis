package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every disconnect reason code of the engine must reach the client as the {@code translate} key
 * of a disconnect packet, in the flavour the current connection state allows.
 */
@EnvTest
public class DisconnectReasonsIntegrationTest {

    @Test
    public void anUnsupportedProtocolVersionIsRejectedWithALoginDisconnect(Env env) throws IOException {
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            final var handshake = new ClientHandshakePacket(775, "localhost", 25565,
                    ClientHandshakePacket.Intent.LOGIN);

            env.process().packetListener().processClientPacket(handshake, connection);

            assertFalse(connection.isOnline(), "the connection must be dropped");
            final ServerPacket packet = pair.readServerPacket(ConnectionState.LOGIN);
            final LoginDisconnectPacket disconnect = assertInstanceOf(LoginDisconnectPacket.class, packet);
            assertEquals("synaxis:unsupported-version", translateKey(disconnect.kickMessage()));
        }
    }

    @Test
    public void aZeroLengthFrameIsRejectedWithAProtocolError(Env env) throws IOException {
        // A frame the peer got wrong is a log line about that connection, not an engine failure.
        // Reporting it as one would print a full stack trace for every hostile frame received.
        final AtomicReference<Throwable> reported = new AtomicReference<>();
        env.process().exception().setExceptionHandler(reported::set);
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.PLAY);

            pair.peer().write(ByteBuffer.wrap(new byte[]{0x00})); // a frame without even a packet id
            connection.read(PacketVanilla.CLIENT_PACKET_PARSER);

            assertFalse(connection.isOnline(), "the connection must be dropped");
            final ServerPacket packet = pair.readServerPacket(ConnectionState.PLAY);
            final DisconnectPacket disconnect = assertInstanceOf(DisconnectPacket.class, packet);
            assertEquals("synaxis:protocol-error", translateKey(disconnect.message()));
            assertNull(reported.get(), "a malformed frame must not be reported as an engine exception");
        }
    }

    @Test
    public void aConnectionStuckBeforeConfigurationTimesOut(Env env) throws IOException {
        assertEquals(30_000, env.process().config().loginTimeout(), "the login timeout is 30 seconds");
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.LOGIN);
            final long expiry = System.currentTimeMillis() + env.process().config().loginTimeout() + 1;

            assertFalse(connection.loginTimedOut(System.currentTimeMillis()), "the connection still has time");
            assertTrue(connection.loginTimedOut(expiry), "a connection stuck in login must time out");

            connection.setClientState(ConnectionState.CONFIGURATION);
            assertFalse(connection.loginTimedOut(expiry), "a configured connection never times out");
            assertEquals("synaxis:login-timeout", DisconnectReasons.LOGIN_TIMEOUT.key());
        }
    }

    @Test
    public void aFailingSpawnDisconnectsWithAnInternalError(Env env) {
        // The failing loader legitimately reports to the exception handler, which would otherwise fail the test
        env.process().exception().setExceptionHandler(_ -> {
        });
        final Instance broken = env.process().instance().createInstanceContainer(new BrokenChunkLoader());
        final var connection = env.createConnection();
        final var disconnects = connection.trackIncoming(DisconnectPacket.class);

        // A failed spawn is reported to its own client and to the log; it never escapes onto the
        // thread letting the queued players in, so the rest of that batch still joins
        var _ = connection.connect(broken, new Pos(0, 42, 0));

        final DisconnectPacket disconnect = awaitDisconnect(disconnects.collect());
        assertEquals("synaxis:internal-error", translateKey(disconnect.message()));
    }

    @Test
    public void aDuplicateUuidIsRejectedWithItsOwnReason(Env env) {
        // The rejection itself is driven end to end by DuplicateUuidLoginIntegrationTest
        assertEquals("synaxis:duplicate-login", DisconnectReasons.DUPLICATE_LOGIN.key());
    }

    @Test
    public void stoppingTheServerDisconnectsEveryPlayer(Env env) {
        final var connection = env.createConnection();
        final var disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        assertTrue(player.isOnline());

        env.process().connection().shutdown();

        disconnects.assertAny();
        disconnects.assertAllMatch(disconnect ->
                "synaxis:server-stopping".equals(translateKey(disconnect.message())));
    }

    private static DisconnectPacket awaitDisconnect(List<DisconnectPacket> collected) {
        // The kick runs on whichever thread completed the spawn future, it may lag behind the join
        for (int i = 0; i < 100 && collected.isEmpty(); i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertEquals(1, collected.size(), "expected exactly one disconnect packet, got " + collected);
        return collected.getFirst();
    }

    private static String translateKey(Component component) {
        return assertInstanceOf(TranslatableComponent.class, component,
                "a disconnect reason must carry its error code as a translate key").key();
    }

    private static final class BrokenChunkLoader implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }

    /**
     * A loopback socket pair, the server side wrapped in a real {@link PlayerSocketConnection}.
     */
    private record SocketPair(ServerSocketChannel server, SocketChannel peer, SocketChannel accepted,
                              PlayerSocketConnection connection, Registries registries) implements AutoCloseable {

        static SocketPair open(Env env) throws IOException {
            final ServerSocketChannel server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final SocketChannel peer = SocketChannel.open(server.getLocalAddress());
            final SocketChannel accepted = server.accept();
            // The current thread stands in for both socket threads, nothing is parked in these tests
            final var connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                    Thread.currentThread(), Thread.currentThread());
            return new SocketPair(server, peer, accepted, connection, env.process().registries());
        }

        ServerPacket readServerPacket(ConnectionState state) throws IOException {
            connection.flushSync();
            final ByteBuffer raw = ByteBuffer.allocate(4096);
            final int length = peer.read(raw);
            assertTrue(length > 0, "the server did not write anything");
            final NetworkBuffer buffer = NetworkBuffer.wrap(raw.array(), 0, length, registries);
            final var result = assertDoesNotThrow(() -> PacketReading.readServers(buffer, state, false));
            if (!(result instanceof PacketReading.Result.Success<ServerPacket> success))
                throw new AssertionError("Expected a packet, got " + result);
            assertEquals(1, success.packets().size(), "expected a single packet, got " + success.packets());
            return success.packets().getFirst().packet();
        }

        @Override
        public void close() throws IOException {
            peer.close();
            accepted.close();
            server.close();
        }
    }
}
