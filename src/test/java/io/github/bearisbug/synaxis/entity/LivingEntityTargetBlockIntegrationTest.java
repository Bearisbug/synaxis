package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnvTest
public class LivingEntityTargetBlockIntegrationTest {

    /** Looking west over the flat ground, across the air of chunk 0 into chunk -1, which is not loaded. */
    @Test
    public void aRayIntoAChunkThatIsNotLoadedFindsNoBlock(Env env) {
        final Instance instance = env.createFlatInstance();
        final LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0.5, 42, 0.5, 90, 0)).join();
        assertNull(instance.getChunk(-1, 0), "chunk -1 is loaded, the ray never leaves loaded chunks");

        assertNull(zombie.getTargetBlockPosition(20));
    }

    /** Looking straight down, the ray stops on the ground within the loaded chunk. */
    @Test
    public void aRayWithinLoadedChunksFindsTheGround(Env env) {
        final LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(env.createFlatInstance(), new Pos(0.5, 42, 0.5, 0, 90)).join();

        final var target = zombie.getTargetBlockPosition(20);
        assertNotNull(target, "no ground under the zombie");
        assertEquals(0, target.blockX());
        assertEquals(0, target.blockZ());
    }
}
