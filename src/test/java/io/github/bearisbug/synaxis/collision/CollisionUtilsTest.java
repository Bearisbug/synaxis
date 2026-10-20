package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CollisionUtilsTest {

    @Test
    public void anEntityOutsideAnInstanceIsRefused() {
        final Entity entity = new Entity(EntityType.ZOMBIE);
        final Vec velocity = new Vec(0, -1, 0);

        final IllegalStateException physics = assertThrows(IllegalStateException.class,
                () -> CollisionUtils.handlePhysics(entity, velocity));
        assertTrue(physics.getMessage().contains("not in an instance"), physics.getMessage());

        final IllegalStateException collisions = assertThrows(IllegalStateException.class,
                () -> CollisionUtils.checkEntityCollisions(entity, velocity, 1.85, _ -> true, null));
        assertTrue(collisions.getMessage().contains("not in an instance"), collisions.getMessage());
    }
}
