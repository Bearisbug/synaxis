package dev.gameengine.listener;

import dev.gameengine.listener.preplay.StatusListener;
import dev.gameengine.network.packet.client.status.StatusRequestPacket;
import dev.gameengine.network.packet.server.SendablePacket;
import dev.gameengine.network.player.PlayerConnection;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Like vanilla, a connection must not be able to ask for the server list JSON repeatedly.
 */
@EnvTest
public class StatusRequestGuardIntegrationTest {

    @Test
    public void onlyTheFirstStatusRequestIsAnswered(Env env) {
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

        StatusListener.requestListener(new StatusRequestPacket(), connection);
        assertEquals(1, sent.size());

        StatusListener.requestListener(new StatusRequestPacket(), connection);
        assertEquals(1, sent.size(), "a repeated status request must not be answered");
        assertFalse(connection.isOnline(), "the connection must be dropped");
    }
}
