package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.DuplicateUuidLoginIntegrationTest.KickCapturingConnection;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private static List<Throwable> captureExceptions(Env env) {
        final List<Throwable> exceptions = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(exceptions::add);
        return exceptions;
    }
}
