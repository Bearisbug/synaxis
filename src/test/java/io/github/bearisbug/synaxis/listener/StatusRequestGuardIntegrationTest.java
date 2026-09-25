package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.listener.preplay.StatusListener;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** A virtual thread like the read thread of a socket connection: the server list event is an {@code AsyncEvent}. */
    private static void onAReadThread(Runnable task) throws ExecutionException, InterruptedException {
        try (ExecutorService readThread = Executors.newVirtualThreadPerTaskExecutor()) {
            readThread.submit(task).get();
        }
    }
}
