package net.minestom.server.listener;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.client.play.ClientConfigurationAckPacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A configuration acknowledgement is only meaningful after the server sent
 * StartConfigurationPacket, otherwise the server would run the configuration phase
 * while its own state is still PLAY.
 */
@EnvTest
public class UnsolicitedConfigurationAckIntegrationTest {

    @Test
    public void configurationAckWithoutStartConfigurationIsRejected(Env env) {
        final Player player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final PlayerConnection connection = player.getPlayerConnection();
        assertEquals(ConnectionState.PLAY, connection.getServerState());

        LoginListener.configAckListener(new ClientConfigurationAckPacket(), player);

        assertFalse(connection.isOnline(), "the connection must be dropped");
    }
}
