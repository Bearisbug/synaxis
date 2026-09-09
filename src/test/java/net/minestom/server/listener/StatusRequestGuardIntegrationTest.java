package net.minestom.server.listener;

import net.minestom.server.listener.preplay.StatusListener;
import net.minestom.server.network.packet.client.status.StatusRequestPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The status response carries the full server list JSON, favicon included, so a
 * connection must not be able to ask for it repeatedly.
 */
@EnvTest
public class StatusRequestGuardIntegrationTest {

    @Test
    public void onlyTheFirstStatusRequestIsAnswered(Env env) throws InterruptedException {
        final List<SendablePacket> sent = new ArrayList<>();
        final PlayerConnection connection = new PlayerConnection() {
            @Override
            public void sendPacket(SendablePacket packet) {
                sent.add(packet);
            }

            @Override
            public SocketAddress getRemoteAddress() {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
            }
        };

        requestStatus(connection);
        assertEquals(1, sent.size());

        requestStatus(connection);
        assertEquals(1, sent.size(), "a repeated status request must not be answered");
        assertFalse(connection.isOnline(), "the connection must be dropped");
    }

    // ServerListPingEvent is an async event, it has to be dispatched from a virtual thread
    private static void requestStatus(PlayerConnection connection) throws InterruptedException {
        Thread.ofVirtual()
                .start(() -> StatusListener.requestListener(new StatusRequestPacket(), connection))
                .join();
    }
}
