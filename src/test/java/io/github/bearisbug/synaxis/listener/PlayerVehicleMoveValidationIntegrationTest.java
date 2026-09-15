package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientVehicleMovePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A vehicle move carries raw float yaw and pitch, and the passenger offset is rotated around the
 * vehicle yaw, so a non finite view has to be rejected before it reaches the riding players.
 */
@EnvTest
public class PlayerVehicleMoveValidationIntegrationTest {

    @Test
    public void nonFiniteVehicleRotationIsRejected(Env env) {
        final var instance = env.createFlatInstance();
        final Pos spawn = new Pos(0, 40, 0);
        final Player player = env.createConnection().connect(instance, spawn);
        final Entity vehicle = new Entity(EntityType.ZOMBIE);
        vehicle.setInstance(instance, spawn).join();
        vehicle.addPassenger(player);
        final Pos riding = player.getPosition();

        PlayerVehicleListener.vehicleMoveListener(
                new ClientVehicleMovePacket(new Pos(1, 40, 1, Float.NaN, 0f), true), player);

        assertFalse(player.isOnline(), "a client sending a NaN vehicle rotation must be kicked");
        assertEquals(riding, player.getPosition(), "a NaN vehicle rotation must not move the passenger");
    }
}
