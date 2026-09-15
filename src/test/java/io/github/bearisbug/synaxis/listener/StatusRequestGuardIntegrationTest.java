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

        StatusListener.requestListener(new StatusRequestPacket(), connection);
        assertEquals(1, sent.size());

        StatusListener.requestListener(new StatusRequestPacket(), connection);
        assertEquals(1, sent.size(), "a repeated status request must not be answered");
        assertFalse(connection.isOnline(), "the connection must be dropped");
    }
}
