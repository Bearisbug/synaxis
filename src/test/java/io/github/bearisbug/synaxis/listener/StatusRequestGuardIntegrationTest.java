package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.event.server.ClientPingServerEvent;
import io.github.bearisbug.synaxis.listener.preplay.StatusListener;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Like vanilla, a connection must not be able to ask for the server list JSON repeatedly.
 */
@EnvTest
public class StatusRequestGuardIntegrationTest {

    @Test
    public void onlyTheFirstStatusRequestIsAnswered(Env env) throws ExecutionException, InterruptedException {
        final List<SendablePacket> sent = new ArrayList<>();
        final PlayerConnection connection = new PlayerConnection(env.process()) {
            @Override
            public void sendPacket(SendablePacket packet) {
                sent.add(packet);
            }

            @Override
            public SocketAddress getRemoteAddress() {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
            }
        };

        onAReadThread(() -> StatusListener.requestListener(new StatusRequestPacket(), connection));
        assertEquals(1, sent.size());

        onAReadThread(() -> StatusListener.requestListener(new StatusRequestPacket(), connection));
        assertEquals(1, sent.size(), "a repeated status request must not be answered");
        assertFalse(connection.isOnline(), "the connection must be dropped");
    }

    /**
     * With a delay, the pong is a scheduled task and the connection stays open until it runs: each
     * further ping on it used to queue one more task and call the event again.
     */
    @Test
    public void onlyTheFirstPingIsAnswered(Env env) throws ExecutionException, InterruptedException {
        final List<SendablePacket> sent = new ArrayList<>();
        final PlayerConnection connection = new PlayerConnection(env.process()) {
            @Override
            public void sendPacket(SendablePacket packet) {
                sent.add(packet);
            }

            @Override
            public SocketAddress getRemoteAddress() {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
            }
        };
        final AtomicInteger pings = new AtomicInteger();
        env.process().eventHandler().addListener(ClientPingServerEvent.class, event -> {
            if (event.getConnection() != connection) return;
            pings.incrementAndGet();
            event.addDelay(Duration.ofSeconds(1));
        });

        onAReadThread(() -> {
            for (int i = 0; i < 1_000; i++) {
                if (!connection.isOnline()) break;
                StatusListener.pingRequestListener(new ClientPingRequestPacket(i), connection);
            }
        });

        assertEquals(1, pings.get(), "only the first ping may be handled");
        assertFalse(connection.isOnline(), "the second ping must drop the connection");
        assertEquals(List.of(), sent, "the pong of the first ping is still waiting for its delay");
    }

    /** A virtual thread like the read thread of a socket connection: the server list event is an {@code AsyncEvent}. */
    private static void onAReadThread(Runnable task) throws ExecutionException, InterruptedException {
        try (ExecutorService readThread = Executors.newVirtualThreadPerTaskExecutor()) {
            readThread.submit(task).get();
        }
    }
}
