package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityLineOfSightIntegrationTest {
    @Test
    public void entityPhysicsCheckLineOfSight(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(10, 42, 0)).join();

        assertEquals(entity2, entity.getLineOfSightEntity(20, (_) -> true));
        assertTrue(entity.hasLineOfSight(entity2, true));

        for (int z = -1; z <= 1; ++z) {
            for (int y = 40; y <= 44; ++y) {
                instance.setBlock(5, y, z, Block.STONE);
            }
        }

        assertNull(entity.getLineOfSightEntity(20, (_) -> true));
        assertFalse(entity.hasLineOfSight(entity2, true));
    }

    @Test
    public void entityPhysicsCheckLineOfSightBehind(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(-10, 42, 0)).join();

        assertNull(entity.getLineOfSightEntity(20, (_) -> true));
        assertFalse(entity.hasLineOfSight(entity2, true));
        assertTrue(entity.hasLineOfSight(entity2, false));

        for (int z = -1; z <= 1; ++z) {
            for (int y = 40; y <= 44; ++y) {
                instance.setBlock(-5, y, z, Block.STONE);
            }
        }

        assertFalse(entity.hasLineOfSight(entity2, false));
    }

    @Test
    public void entityPhysicsCheckLineOfSightNearMiss(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(10, 42, 0.31)).join();

        assertNull(entity.getLineOfSightEntity(20, (_) -> true));
        assertFalse(entity.hasLineOfSight(entity2, true));
        assertTrue(entity.hasLineOfSight(entity2, false));

        for (int z = -1; z <= 1; ++z) {
            for (int y = 40; y <= 44; ++y) {
                instance.setBlock(5, y, z, Block.STONE);
            }
        }

        assertFalse(entity.hasLineOfSight(entity2, false));
    }

    @Test
    public void entityPhysicsCheckLineOfSightNearHit(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(10, 42, 0.3)).join();

        assertEquals(entity2, entity.getLineOfSightEntity(20, (_) -> true));
        assertTrue(entity.hasLineOfSight(entity2, true));
        assertTrue(entity.hasLineOfSight(entity2, false));

        for (int z = -1; z <= 1; ++z) {
            for (int y = 40; y <= 44; ++y) {
                instance.setBlock(5, y, z, Block.STONE);
            }
        }

        assertNull(entity.getLineOfSightEntity(20, (_) -> true));
        assertFalse(entity.hasLineOfSight(entity2, true));
        assertFalse(entity.hasLineOfSight(entity2, false));
    }

    @Test
    public void entityPhysicsCheckLineOfSightCorrectOrder(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(10, 42, 0)).join();

        var entity3 = new Entity(EntityTypes.ZOMBIE);
        entity3.setInstance(instance, new Pos(5, 42, 0)).join();

        assertEquals(entity3, entity.getLineOfSightEntity(20, (_) -> true));
        assertTrue(entity.hasLineOfSight(entity2, true));
        assertTrue(entity.hasLineOfSight(entity2, false));
        assertTrue(entity.hasLineOfSight(entity3, true));
        assertTrue(entity.hasLineOfSight(entity3, false));
    }

    @Test
    public void entityPhysicsCheckLineOfSightBigMiss(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(10, 42, 10)).join();

        assertNull(entity.getLineOfSightEntity(20, (_) -> true));
        assertFalse(entity.hasLineOfSight(entity2, true));
        assertTrue(entity.hasLineOfSight(entity2, false));
    }

    @Test
    public void entityPhysicsCheckLineOfSightLargeBoundingBox(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(6, 42, 0)).join();
        entity2.setBoundingBox(4.0, 2.0, 4.0);

        for (int z = -1; z <= 1; ++z) {
            for (int y = 40; y <= 44; ++y) {
                instance.setBlock(5, y, z, Block.STONE);
            }
        }

        assertEquals(entity2, entity.getLineOfSightEntity(20, (_) -> true));
        assertTrue(entity.hasLineOfSight(entity2, true));
        assertTrue(entity.hasLineOfSight(entity2, false));
    }

    @Test
    public void entityPhysicsCheckLineOfSightDifferentTypes(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.CHICKEN);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.setView(-90, 0);

        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(10, 42, 0)).join();

        assertEquals(entity2, entity.getLineOfSightEntity(20, (_) -> true));
        assertTrue(entity.hasLineOfSight(entity2, true));

        entity.teleport(new Pos(10, 42, 0)).join();
        entity2.teleport(new Pos(0, 42, 0)).join();
        entity2.setView(-90, 0);

        assertNull(entity2.getLineOfSightEntity(20, (_) -> true));
        assertFalse(entity2.hasLineOfSight(entity, true));
        assertTrue(entity2.hasLineOfSight(entity, false));
    }

    @Test
    public void entityPhysicsCheckLineOfSightGrazingAPillar(Env env) {
        var instance = env.createFlatInstance();

        var viewer = new Entity(EntityTypes.ZOMBIE);
        viewer.setInstance(instance, new Pos(3.0, 42, 0.5)).join();
        var target = new Entity(EntityTypes.ZOMBIE);
        target.setInstance(instance, new Pos(5.29, 42, 7.37)).join();

        assertTrue(viewer.hasLineOfSight(target));

        // The ray enters the pillar through its west face at z = 6.5, it must stop there instead of sliding along it
        for (int y = 40; y <= 45; ++y) {
            instance.setBlock(5, y, 5, Block.STONE);
            instance.setBlock(5, y, 6, Block.STONE);
        }

        assertFalse(viewer.hasLineOfSight(target));
    }

    @Test
    public void entityPhysicsCheckLineOfSightSameEyes(Env env) {
        var instance = env.createFlatInstance();

        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        var entity2 = new Entity(EntityTypes.ZOMBIE);
        entity2.setInstance(instance, new Pos(0, 42, 0)).join();

        // No direction from one pair of eyes to the other
        assertFalse(entity.hasLineOfSight(entity2, false));
    }
}
