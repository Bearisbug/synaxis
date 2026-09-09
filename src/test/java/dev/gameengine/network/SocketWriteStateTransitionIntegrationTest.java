package dev.gameengine.network;

import dev.gameengine.network.packet.PacketWriting;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.common.PluginMessagePacket;
import dev.gameengine.network.packet.server.configuration.FinishConfigurationPacket;
import dev.gameengine.network.player.PlayerSocketConnection;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A packet that changes the connection state may be rejected by a full write buffer and
 * retried on the next flush, so the state must only move once the packet is really written.
 */
@EnvTest
public class SocketWriteStateTransitionIntegrationTest {

    @BeforeAll
    public static void setup(Env env) { // PACKET_POOL
        assertNotNull(env.process().registries());
    }

    @Test
    public void aRejectedStateChangingPacketDoesNotAdvanceTheServerState(Env env) throws IOException {
        // Leave two free bytes in the pooled buffer, less than the smallest frame header
        final int overhead = framedSize(env, new PluginMessagePacket("ch", new byte[0]));
        final var filler = new PluginMessagePacket("ch", new byte[env.process().config().pooledBufferSize() - 2 - overhead]);
        assertEquals(env.process().config().pooledBufferSize() - 2, framedSize(env, filler));

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                assertTrue(peer.isConnected());
                final var connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                        Thread.currentThread(), Thread.currentThread());
                connection.setClientState(ConnectionState.CONFIGURATION);
                assertEquals(ConnectionState.CONFIGURATION, connection.getServerState());

                connection.sendPacket(filler);
                connection.sendPacket(new FinishConfigurationPacket());

                connection.flushSync();
                assertEquals(ConnectionState.CONFIGURATION, connection.getServerState(),
                        "the state must not move while the packet is still queued");

                assertDoesNotThrow(connection::flushSync);
                assertEquals(ConnectionState.PLAY, connection.getServerState());
            }
        }
    }

    private static int framedSize(Env env, ServerPacket packet) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64, env.process().registries());
        PacketWriting.writeFramedPacket(buffer, ConnectionState.CONFIGURATION, packet, 0);
        return (int) buffer.writeIndex();
    }
}
