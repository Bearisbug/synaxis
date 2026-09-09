package dev.gameengine.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import dev.gameengine.ServerFlag;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Player;
import dev.gameengine.instance.Chunk;
import dev.gameengine.instance.ChunkLoader;
import dev.gameengine.instance.Instance;
import dev.gameengine.network.packet.PacketReading;
import dev.gameengine.network.packet.PacketVanilla;
import dev.gameengine.network.packet.client.handshake.ClientHandshakePacket;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.common.DisconnectPacket;
import dev.gameengine.network.packet.server.login.LoginDisconnectPacket;
import dev.gameengine.network.player.PlayerSocketConnection;
import dev.gameengine.registry.Registries;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertEquals("engine:unsupported-version", translateKey(disconnect.kickMessage()));
        }
    }

    @Test
    public void aZeroLengthFrameIsRejectedWithAProtocolError(Env env) throws IOException {
        // A malformed frame is reported to the exception handler, which would otherwise fail the test
        env.process().exception().setExceptionHandler(_ -> {
        });
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.PLAY);

            pair.peer().write(ByteBuffer.wrap(new byte[]{0x00})); // a frame without even a packet id
            connection.read(PacketVanilla.CLIENT_PACKET_PARSER);

            assertFalse(connection.isOnline(), "the connection must be dropped");
            final ServerPacket packet = pair.readServerPacket(ConnectionState.PLAY);
            final DisconnectPacket disconnect = assertInstanceOf(DisconnectPacket.class, packet);
            assertEquals("engine:protocol-error", translateKey(disconnect.message()));
        }
    }

    @Test
    public void aConnectionStuckBeforeConfigurationTimesOut(Env env) throws IOException {
        assertEquals(30_000, ServerFlag.LOGIN_TIMEOUT, "the login timeout is 30 seconds");
        try (SocketPair pair = SocketPair.open(env)) {
            final PlayerSocketConnection connection = pair.connection();
            connection.setClientState(ConnectionState.LOGIN);
            final long expiry = System.currentTimeMillis() + ServerFlag.LOGIN_TIMEOUT + 1;

            assertFalse(connection.loginTimedOut(System.currentTimeMillis()), "the connection still has time");
            assertTrue(connection.loginTimedOut(expiry), "a connection stuck in login must time out");

            connection.setServerState(ConnectionState.CONFIGURATION);
            assertFalse(connection.loginTimedOut(expiry), "a configured connection never times out");
            assertEquals("engine:login-timeout", DisconnectReasons.LOGIN_TIMEOUT.key());
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

        assertThrows(CompletionException.class, () -> connection.connect(broken, new Pos(0, 42, 0)),
                "the spawn must fail");

        final DisconnectPacket disconnect = awaitDisconnect(disconnects.collect());
        assertEquals("engine:internal-error", translateKey(disconnect.message()));
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
                "engine:server-stopping".equals(translateKey(disconnect.message())));
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
            final var connection = new PlayerSocketConnection(accepted, accepted.getRemoteAddress(),
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
