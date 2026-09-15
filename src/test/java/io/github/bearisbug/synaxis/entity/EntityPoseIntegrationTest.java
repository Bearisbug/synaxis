package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class EntityPoseIntegrationTest {

    @Test
    public void deadEntityKeepsTheDyingPose(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 40, 0)).join();

        zombie.kill();
        assertEquals(EntityPose.DYING, zombie.getPose());

        zombie.acquirable().sync(_ -> zombie.updatePose());
        assertEquals(EntityPose.DYING, zombie.getPose(), "updatePose overwrote the dying pose");
    }

    @Test
    public void deadPlayerKeepsTheDyingPose(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));

        player.kill();
        env.tick();
        assertEquals(EntityPose.DYING, player.getPose(), "updatePose overwrote the dying pose");
    }

    @Test
    public void sleepingEntityUsesTheSleepingPose(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var villager = new LivingEntity(EntityType.VILLAGER);
        villager.setInstance(instance, new Pos(0, 40, 0)).join();

        villager.getLivingEntityMeta().setBedInWhichSleepingPosition(new Pos(0, 40, 0));
        villager.acquirable().sync(_ -> villager.updatePose());
        assertEquals(EntityPose.SLEEPING, villager.getPose());
    }
}
