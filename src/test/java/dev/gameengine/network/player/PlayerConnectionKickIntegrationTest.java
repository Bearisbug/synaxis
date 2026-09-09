package dev.gameengine.network.player;

import net.kyori.adventure.text.Component;
import dev.gameengine.network.ConnectionState;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Neither the handshake nor the status registry has a disconnect packet, so kicking in
 * those states must not queue one.
 */
@EnvTest
public class PlayerConnectionKickIntegrationTest {

    @Test
    public void kickingAStatusConnectionDoesNotQueueADisconnectPacket(Env env) throws IOException {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                assertTrue(peer.isConnected());
                final var connection = new PlayerSocketConnection(accepted, accepted.getRemoteAddress(),
                        Thread.currentThread(), Thread.currentThread());
                connection.setClientState(ConnectionState.STATUS);

                connection.kick(Component.text("bye"));
                assertFalse(connection.isOnline());
                assertDoesNotThrow(connection::flushSync);
            }
        }
    }
}
