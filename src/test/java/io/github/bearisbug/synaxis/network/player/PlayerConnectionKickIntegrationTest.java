package io.github.bearisbug.synaxis.network.player;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a kick queues: no disconnect packet in the handshake and status states, whose registries
 * have none, and no second one for a connection that is already gone.
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
                final var connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                        Thread.currentThread(), Thread.currentThread());
                connection.setClientState(ConnectionState.STATUS);

                connection.kick(Component.text("bye"));
                assertFalse(connection.isOnline());
                assertDoesNotThrow(connection::flushSync);
            }
        }
    }

    /**
     * A connection is kicked once. Whatever decides to kick it after that, such as the outbound
     * backlog check or a login replacing it, finds it gone and queues no second disconnect packet.
     */
    @Test
    public void aConnectionAlreadyGoneIsNotKickedAgain(Env env) throws IOException {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                assertTrue(peer.isConnected());
                final var connection = new PlayerSocketConnection(env.process(), accepted, accepted.getRemoteAddress(),
                        Thread.currentThread(), Thread.currentThread());
                connection.setServerState(ConnectionState.LOGIN);

                connection.kick(Component.text("bye"));
                connection.kick(Component.text("bye again"));

                assertFalse(connection.isOnline());
                assertEquals(PlayerSocketConnection.UNFRAMED_PACKET_BYTES, connection.backlogBytes(),
                        "the second kick queued another disconnect packet");
            }
        }
    }
}
