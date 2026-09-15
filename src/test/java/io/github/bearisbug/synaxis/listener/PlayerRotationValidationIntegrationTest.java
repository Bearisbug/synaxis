package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerRotationPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
