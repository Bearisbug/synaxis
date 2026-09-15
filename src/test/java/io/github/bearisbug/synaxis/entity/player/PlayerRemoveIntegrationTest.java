package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

@EnvTest
public class PlayerRemoveIntegrationTest {

    @Test
    public void removingAnOnlinePlayerCallsTheDisconnectEventOnce(Env env) {
        var instance = env.createFlatInstance();
        // The real connection is used because Player#remove kicks the player, and the disconnect
        // handling is where the event used to be called a second time.
        final var connection = new SilentConnection(env.process());
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Leaver"));
        player.setInstance(instance, new Pos(0, 40, 0)).join();

        var disconnects = env.trackEvent(PlayerDisconnectEvent.class, EventFilter.PLAYER, player);
        player.remove();

        disconnects.assertCount(1);
    }

    private static final class SilentConnection extends PlayerConnection {
        private SilentConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
