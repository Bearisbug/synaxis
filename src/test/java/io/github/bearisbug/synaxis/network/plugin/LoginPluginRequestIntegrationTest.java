package io.github.bearisbug.synaxis.network.plugin;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login plugin requests a pre login listener sends ({@code AsyncPlayerPreLoginEvent#sendPluginRequest}) and
 * the answers the client sends back.
 */
@EnvTest
public class LoginPluginRequestIntegrationTest {

    /** A connection that records what it is sent and never answers. */
    private static final class SilentConnection extends PlayerConnection {
        private final List<SendablePacket> sent = new CopyOnWriteArrayList<>();

        private SilentConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            sent.add(packet);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }

    private static AsyncPlayerPreLoginEvent preLogin(SilentConnection connection) {
        connection.setServerState(ConnectionState.LOGIN);
        return new AsyncPlayerPreLoginEvent(connection, new GameProfile(UUID.randomUUID(), "Silent"),
                connection.loginPluginMessageProcessor());
    }

    /**
     * A listener that waits for the answer of a client that never sends one: the disconnect ends the
     * wait, instead of keeping the login thread, the event and the connection for good.
     */
    @Test
    public void aDisconnectFailsTheRequestStillWaitingForItsAnswer(Env env) throws InterruptedException {
        final SilentConnection connection = new SilentConnection(env.process());
        final CompletableFuture<LoginPlugin.Response> answer = preLogin(connection)
                .sendPluginRequest("synaxis:test", new byte[0]);
        assertInstanceOf(LoginPluginRequestPacket.class, connection.sent.getFirst());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread waiter = Thread.startVirtualThread(() -> {
            try {
                answer.join();
            } catch (CompletionException e) {
                failure.set(e);
            }
        });

        connection.disconnect();
        waiter.join(Duration.ofSeconds(1));

        assertFalse(waiter.isAlive(), "the wait must end with the connection");
        assertInstanceOf(IllegalStateException.class, assertInstanceOf(CompletionException.class, failure.get()).getCause());
    }

    /**
     * A callback may run long after the event returned, with the connection in the configuration
     * state by then: the request is refused where it is made rather than queued in a state that has
     * no such packet, and the requests still waiting fail.
     */
    @Test
    public void leavingTheLoginStateEndsItsRequests(Env env) {
        final SilentConnection connection = new SilentConnection(env.process());
        final AsyncPlayerPreLoginEvent event = preLogin(connection);
        final CompletableFuture<LoginPlugin.Response> pending = event.sendPluginRequest("synaxis:test", new byte[0]);

        connection.setServerState(ConnectionState.CONFIGURATION);

        assertTrue(pending.isCompletedExceptionally(), "the request still waiting for its answer fails");
        assertThrows(IllegalStateException.class, () -> event.sendPluginRequest("synaxis:test", new byte[0]));
        assertEquals(1, connection.sent.size(), "nothing is queued once the connection left the login state");
    }

    /**
     * An answer nobody asked for is refused naming what the peer sent, no payload at all included: a
     * client that does not know the channel answers without one.
     */
    @Test
    public void anAnswerNobodyAskedForIsRefusedEvenWithoutAPayload(Env env) {
        final LoginPluginMessageProcessor processor = new SilentConnection(env.process()).loginPluginMessageProcessor();

        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> processor.handleResponse(42, null));
        assertEquals("Received unexpected Login Plugin Response id 42 of no bytes", refused.getMessage());
    }
}
