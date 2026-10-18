package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
public class PlacementCollisionIntegrationTest {

    @Test
    public void empty(Env env) {
        var instance = env.createFlatInstance();
        assertNull(BlockCollision.canPlaceBlockAt(instance, new Vec(0, 40, 0), Block.STONE));
    }

    @Test
    public void entityBlock(Env env) {
        var instance = env.createFlatInstance();
        new Entity(EntityType.ZOMBIE).setInstance(instance, new Pos(0, 40, 0)).join();
        assertNotNull(BlockCollision.canPlaceBlockAt(instance, new Vec(0, 40, 0), Block.STONE));
    }

    @Test
    public void slab(Env env) {
        var instance = env.createFlatInstance();
        new Entity(EntityType.ZOMBIE).setInstance(instance, new Pos(0, 40.75, 0)).join();
        assertNull(BlockCollision.canPlaceBlockAt(instance, new Vec(0, 40, 0), Block.STONE_SLAB));
    }

    @Test
    public void largeEntityReachingFarFromItsFeet(Env env) {
        var instance = env.createFlatInstance();
        // The box spans (0.95..2.35, 40..42.7, 0.95..2.35): it reaches into the block, its feet are 3.07 from the block corner
        var golem = new Entity(EntityType.IRON_GOLEM);
        golem.setInstance(instance, new Pos(1.65, 40, 1.65)).join();
        assertSame(golem, BlockCollision.canPlaceBlockAt(instance, new Vec(0, 42, 0), Block.STONE));
        assertNull(BlockCollision.canPlaceBlockAt(instance, new Vec(0, 43, 0), Block.STONE));
    }

    @Test
    public void giantReachingIntoABlockAboveIt(Env env) {
        var instance = env.createFlatInstance();
        // 3.6 wide and 12 tall: the box spans (0.7..4.3, 40..52, 0.7..4.3)
        var giant = new Entity(EntityType.GIANT);
        giant.setInstance(instance, new Pos(2.5, 40, 2.5)).join();
        assertSame(giant, BlockCollision.canPlaceBlockAt(instance, new Vec(0, 51, 0), Block.STONE));
    }

    @Test
    public void belowPlayer(Env env) {
        var instance = env.createFlatInstance();
        env.createPlayer(instance, new Pos(5.7, -8, 6.389));
        assertNull(BlockCollision.canPlaceBlockAt(instance, new Vec(5, -9, 6), Block.STONE));
    }
}
