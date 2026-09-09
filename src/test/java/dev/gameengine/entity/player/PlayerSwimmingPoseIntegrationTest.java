package dev.gameengine.entity.player;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.EntityPose;
import dev.gameengine.instance.block.Block;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class PlayerSwimmingPoseIntegrationTest {

    @Test
    public void sprintingWithSubmergedEyesSwims(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.setBlock(0, 40, 0, Block.WATER);
        instance.setBlock(0, 41, 0, Block.WATER);

        var player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        player.setSprinting(true);
        env.tick();

        assertEquals(EntityPose.SWIMMING, player.getPose());
    }

    @Test
    public void sprintingOnLandDoesNotSwim(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        var player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        player.setSprinting(true);
        env.tick();

        assertEquals(EntityPose.STANDING, player.getPose());
    }

    @Test
    public void leavingTheWaterStopsSwimming(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.setBlock(0, 40, 0, Block.WATER);
        instance.setBlock(0, 41, 0, Block.WATER);

        var player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        player.setSprinting(true);
        env.tick();
        assertEquals(EntityPose.SWIMMING, player.getPose());

        instance.setBlock(0, 40, 0, Block.AIR);
        instance.setBlock(0, 41, 0, Block.AIR);
        env.tick();
        assertEquals(EntityPose.STANDING, player.getPose());
    }
}
