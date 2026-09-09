package net.minestom.server.entity;

import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class LivingEntityHealthIntegrationTest {

    @Test
    public void livingEntitySpawnsAtFullHealth() {
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        assertEquals(20f, zombie.getHealth(), "a living entity must not spawn with a single health point");
        assertEquals(20f, zombie.getLivingEntityMeta().getHealth(), "health metadata was never written");
    }
}
