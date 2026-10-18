package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerConnectionGuardIntegrationTest {

    /** A connection that only records what it is sent; its disconnection is the engine's own. */
    private static final class RecordingConnection extends PlayerConnection {
        private final List<SendablePacket> sent = new CopyOnWriteArrayList<>();

        private RecordingConnection(ServerProcess process) {
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

    /**
     * The read thread and the write thread can both drop a connection in the configuration state at
     * once. Each of them used to decide whether to report the player by looking it up, so both did.
     */
    @Test
    public void twoThreadsDroppingAConfigurationConnectionReportThePlayerOnce(Env env) throws InterruptedException {
        final RecordingConnection connection = new RecordingConnection(env.process());
        connection.setClientState(ConnectionState.CONFIGURATION);
        connection.setServerState(ConnectionState.CONFIGURATION);
        final ConnectionManager connections = env.process().connection();
        final Player player = connections.createPlayer(connection, new GameProfile(UUID.randomUUID(), "Dropped"));
        assertNotNull(player);
        final AtomicInteger disconnects = new AtomicInteger();
        env.process().eventHandler().addListener(PlayerDisconnectEvent.class, event -> {
            if (event.getPlayer() == player) disconnects.incrementAndGet();
        });

        final Thread first = new Thread(connection::disconnect, "test-read-thread");
        final Thread second = new Thread(connection::disconnect, "test-write-thread");
        // Both threads get as far as the removal from the manager and wait there for its monitor
        synchronized (connections) {
            first.start();
            second.start();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!waitsOnMe(first) || !waitsOnMe(second)) {
                assertTrue(System.nanoTime() < deadline, "the two threads never got to the connection manager");
                Thread.sleep(1);
            }
        }
        first.join(5_000);
        second.join(5_000);

        assertEquals(1, disconnects.get(), "the player must be reported once");
    }

    private static boolean waitsOnMe(Thread thread) {
        final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
        return info != null && info.getLockOwnerId() == Thread.currentThread().threadId();
    }

    /**
     * Neither packet exists in the login state: queued then, the write thread fails on it and drops
     * the connection far from the call. The call refuses it instead.
     */
    @Test
    public void storeCookieAndTransferNeedTheConfigurationOrPlayState(Env env) {
        final RecordingConnection connection = new RecordingConnection(env.process());
        connection.setServerState(ConnectionState.LOGIN);

        final var cookie = assertThrows(IllegalStateException.class, () -> connection.storeCookie("probe:a", new byte[1]));
        assertTrue(cookie.getMessage().contains("LOGIN"), cookie.getMessage());
        final var transfer = assertThrows(IllegalStateException.class, () -> connection.transfer("localhost", 25566));
        assertTrue(transfer.getMessage().contains("LOGIN"), transfer.getMessage());
        assertEquals(List.of(), connection.sent, "nothing may be queued in the login state");

        connection.setServerState(ConnectionState.CONFIGURATION);
        connection.storeCookie("probe:a", new byte[1]);
        assertEquals(1, connection.sent.size());
        assertInstanceOf(CookieStorePacket.class, connection.sent.getFirst());
    }
}
