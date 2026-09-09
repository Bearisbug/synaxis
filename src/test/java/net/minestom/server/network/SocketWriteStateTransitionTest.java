package net.minestom.server.network;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
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
public class SocketWriteStateTransitionTest {

    @BeforeAll
    public static void setup(Env env) { // PACKET_POOL
        assertNotNull(env.process().registries());
    }

    @Test
    public void aRejectedStateChangingPacketDoesNotAdvanceTheServerState() throws IOException {
        // Leave two free bytes in the pooled buffer, less than the smallest frame header
        final int overhead = framedSize(new PluginMessagePacket("ch", new byte[0]));
        final var filler = new PluginMessagePacket("ch", new byte[ServerFlag.POOLED_BUFFER_SIZE - 2 - overhead]);
        assertEquals(ServerFlag.POOLED_BUFFER_SIZE - 2, framedSize(filler));

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                assertTrue(peer.isConnected());
                final var connection = new PlayerSocketConnection(accepted, accepted.getRemoteAddress(),
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

    private static int framedSize(ServerPacket packet) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64, MinecraftServer.getRegistries());
        PacketWriting.writeFramedPacket(buffer, ConnectionState.CONFIGURATION, packet, 0);
        return (int) buffer.writeIndex();
    }
}
