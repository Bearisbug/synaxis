package dev.gameengine.listener;

import net.kyori.adventure.text.TranslatableComponent;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Player;
import dev.gameengine.listener.preplay.LoginListener;
import dev.gameengine.network.ConnectionState;
import dev.gameengine.network.packet.client.play.ClientConfigurationAckPacket;
import dev.gameengine.network.packet.server.common.DisconnectPacket;
import dev.gameengine.network.player.PlayerConnection;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        final var testConnection = env.createConnection();
        final Player player = testConnection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final var disconnects = testConnection.trackIncoming(DisconnectPacket.class);
        final PlayerConnection connection = player.getPlayerConnection();
        assertEquals(ConnectionState.PLAY, connection.getServerState());

        LoginListener.configAckListener(new ClientConfigurationAckPacket(), player);

        assertFalse(connection.isOnline(), "the connection must be dropped");
        disconnects.assertSingle(disconnect -> assertEquals("engine:protocol-error",
                assertInstanceOf(TranslatableComponent.class, disconnect.message()).key()));
    }
}
