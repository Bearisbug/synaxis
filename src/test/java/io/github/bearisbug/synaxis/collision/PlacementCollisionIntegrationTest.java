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
    public void belowPlayer(Env env) {
        var instance = env.createFlatInstance();
        env.createPlayer(instance, new Pos(5.7, -8, 6.389));
        assertNull(BlockCollision.canPlaceBlockAt(instance, new Vec(5, -9, 6), Block.STONE));
    }
}
