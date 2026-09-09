package net.minestom.server.listener;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.client.play.ClientPlayerRotationPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Rotation is sent as raw floats, so NaN and infinity have to be rejected before they
 * reach the entity view and get broadcast to viewers.
 */
@EnvTest
public class PlayerRotationValidationIntegrationTest {

    @Test
    public void nonFiniteRotationIsRejected(Env env) {
        final Player player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 40, 0));

        PlayerPositionListener.playerLookListener(new ClientPlayerRotationPacket(Float.NaN, 0f, true, false), player);

        assertEquals(0f, player.getPosition().yaw());
        assertFalse(player.isOnline(), "a client sending NaN rotation must be kicked");
    }
}
