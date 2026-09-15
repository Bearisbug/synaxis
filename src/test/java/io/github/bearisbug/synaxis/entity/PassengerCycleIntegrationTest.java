package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PassengerCycleIntegrationTest {

    @Test
    public void indirectCycleIsRejected(Env env) {
        var instance = env.createFlatInstance();
        var a = spawn(instance);
        var b = spawn(instance);
        var c = spawn(instance);

        a.addPassenger(b);
        b.addPassenger(c);

        // c already rides a through b, mounting a on c would close the ride chain into a cycle
        assertThrows(IllegalStateException.class, () -> c.addPassenger(a));
        assertTrue(c.getPassengers().isEmpty());
        assertNull(a.getVehicle());
    }

    @Test
    public void selfMountIsRejected(Env env) {
        var entity = spawn(env.createFlatInstance());

        assertThrows(IllegalStateException.class, () -> entity.addPassenger(entity));
        assertTrue(entity.getPassengers().isEmpty());
        assertNull(entity.getVehicle());
    }

    private static Entity spawn(Instance instance) {
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 40, 0)).join();
        return entity;
    }
}
