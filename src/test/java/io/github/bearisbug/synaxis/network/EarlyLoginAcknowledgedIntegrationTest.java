package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.DuplicateUuidLoginIntegrationTest.KickCapturingConnection;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The client acknowledges a login only once it read the login success ({@code docs/DESIGN.md} §11.1,
 * login → configuration). The packet moves the decoding state on its own type, so a peer can send
 * it at any point of the login; one that arrives before the server wrote the login success is the
 * peer breaking the protocol, and ends the connection as such, with no player created and nothing
 * reported to the exception handler.
 */
@EnvTest
public class EarlyLoginAcknowledgedIntegrationTest {

    /** Right behind the handshake: no login start was handled, there is no profile yet. */
    @Test
    public void anAcknowledgementWithoutAProfileIsAProtocolError(Env env) throws IOException {
        final List<Throwable> exceptions = captureExceptions(env);
        try (KickCapturingConnection connection = new KickCapturingConnection(env.process())) {
            connection.setServerState(ConnectionState.LOGIN);

            LoginListener.loginAckListener(new ClientLoginAcknowledgedPacket(), connection);

            assertEquals("synaxis:protocol-error", connection.kickKey());
            assertNull(env.process().connection().getPlayer(connection), "a player was created for the connection");
        }
        assertEquals(List.of(), exceptions, "the early acknowledgement reported an exception");
    }

    /** The profile is decided, the login success is not written yet: the server is still in the login state. */
    @Test
    public void anAcknowledgementBeforeTheLoginSuccessIsAProtocolError(Env env) throws IOException {
        final List<Throwable> exceptions = captureExceptions(env);
        try (KickCapturingConnection connection = new KickCapturingConnection(env.process())) {
            connection.UNSAFE_setProfile(new GameProfile(UUID.randomUUID(), "early000"));
            connection.setServerState(ConnectionState.LOGIN);

            LoginListener.loginAckListener(new ClientLoginAcknowledgedPacket(), connection);

            assertEquals("synaxis:protocol-error", connection.kickKey());
            assertNull(env.process().connection().getPlayer(connection), "a player was created for the connection");
        }
        assertEquals(List.of(), exceptions, "the early acknowledgement reported an exception");
    }

    /**
     * Offline, a handshake, a login start and an acknowledgement in one write, read off a loopback
     * socket: the login start hands the login over to a virtual thread, and the acknowledgement right
     * behind it is handled on the read thread while that thread has written no login success. The
     * pre-login event holds the virtual thread until the read is done, the profile is published only
     * after it.
     */
    @Test
    public void anAcknowledgementInTheReadOfItsLoginStartIsAProtocolError(Env env) throws IOException {
        final List<Throwable> exceptions = captureExceptions(env);
        final CountDownLatch readDone = new CountDownLatch(1);
        env.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, _ -> {
            try {
                readDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(server.getLocalAddress());
                 KickCapturingConnection connection = new KickCapturingConnection(env.process(), server.accept())) {
                final NetworkBuffer frames = NetworkBuffer.resizableBuffer();
                PacketWriting.writeFramedPacket(frames, ConnectionState.HANDSHAKE, new ClientHandshakePacket(
                        MinecraftServer.PROTOCOL_VERSION, "localhost", 25565, ClientHandshakePacket.Intent.LOGIN), 0);
                PacketWriting.writeFramedPacket(frames, ConnectionState.LOGIN,
                        new ClientLoginStartPacket("early001", UUID.randomUUID()), 0);
                PacketWriting.writeFramedPacket(frames, ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket(), 0);
                while (!frames.writeChannel(peer)) {
                    // A blocking channel writes everything
                }

                connection.read(PacketVanilla.CLIENT_PACKET_PARSER);

                assertEquals("synaxis:protocol-error", connection.kickKey());
                assertNull(env.process().connection().getPlayer(connection), "a player was created for the connection");
            }
        } finally {
            readDone.countDown();
        }
        assertEquals(List.of(), exceptions, "the early acknowledgement reported an exception");
    }

    private static List<Throwable> captureExceptions(Env env) {
        final List<Throwable> exceptions = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(exceptions::add);
        return exceptions;
    }
}
