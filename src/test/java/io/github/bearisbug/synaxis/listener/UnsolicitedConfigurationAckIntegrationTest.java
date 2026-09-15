package io.github.bearisbug.synaxis.listener;

import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
        disconnects.assertSingle(disconnect -> assertEquals("synaxis:protocol-error",
                assertInstanceOf(TranslatableComponent.class, disconnect.message()).key()));
    }
}
