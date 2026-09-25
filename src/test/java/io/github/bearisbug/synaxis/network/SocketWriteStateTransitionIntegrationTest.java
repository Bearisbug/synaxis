package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

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
@ResourceLock(SocketWriteStateTransitionIntegrationTest.PACKET_POOL)
public class SocketWriteStateTransitionIntegrationTest {

    /**
     * The pooled buffers are shared by the whole JVM: a test that grows some of them past the default
     * size while this one runs would hand it a buffer the filler below no longer fills.
     */
    static final String PACKET_POOL = "io.github.bearisbug.synaxis.network.packet.PacketVanilla#PACKET_POOL";

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
