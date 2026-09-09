package net.minestom.server.listener;

import net.kyori.adventure.text.TranslatableComponent;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.client.play.ClientConfigurationAckPacket;
import net.minestom.server.network.packet.server.common.DisconnectPacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
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
