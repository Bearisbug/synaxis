package io.github.bearisbug.synaxis.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LivingEntityTargetBlockTest {

    /** With no positive distance there is no ray to walk: a zero one ended nowhere, a negative one behind the eyes. */
    @Test
    public void aDistanceThatIsNotPositiveIsRefused() {
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        assertThrows(IllegalArgumentException.class, () -> zombie.getTargetBlockPosition(0));
        assertThrows(IllegalArgumentException.class, () -> zombie.getTargetBlockPosition(-1));
    }

    @Test
    public void anEntityInNoInstanceTargetsNoBlock() {
        assertNull(new LivingEntity(EntityType.ZOMBIE).getTargetBlockPosition(5));
    }
}
