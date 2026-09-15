package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPacketOutEvent;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A packet that does not fit in the write buffer is written again on a bigger one, so the outgoing
 * event has to be dispatched once per queued packet rather than once per write attempt.
 */
@EnvTest
public class PacketOutEventRetryIntegrationTest {

    @Test
    public void aPacketRetriedOnABiggerBufferDispatchesTheOutgoingEventOnce(Env env) throws IOException {
        final var instance = env.createFlatInstance();
        final Player player = env.createConnection().connect(instance, new Pos(0, 40, 0));
        final AtomicInteger dispatches = new AtomicInteger();
        env.process().eventHandler().addListener(PlayerPacketOutEvent.class, event -> {
            if (event.getPacket() instanceof PluginMessagePacket) dispatches.incrementAndGet();
        });

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                assertTrue(peer.isConnected());
                final var connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                        Thread.currentThread(), Thread.currentThread());
                connection.setClientState(ConnectionState.PLAY);
                connection.setPlayer(player);

                // Larger than the pooled write buffer, the write has to grow it and start over
                final int payload = env.process().config().pooledBufferSize();
                connection.sendPacket(new PluginMessagePacket("ch", new byte[payload]));
                assertDoesNotThrow(connection::flushSync);

                assertEquals(1, dispatches.get(),
                        "a packet written on a second attempt must not be dispatched twice");
            }
        }
    }
}
