package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityEntityCollisionIntegrationTest {
    @Test
    public void entitySingleCollisionTest(Env env) {
        var instance = env.createFlatInstance();

        for (int i = -2; i <= 2; ++i)
            for (int j = -2; j <= 2; ++j)
                instance.loadChunk(i, j).join();

        var movingEntity = new Entity(EntityType.ZOMBIE);
        var stillEntity = new Entity(EntityType.ZOMBIE);
        var doNotHitEntity = new Entity(EntityType.ZOMBIE);

        movingEntity.setInstance(instance, new Vec(0, 42, 0)).join();
        stillEntity.setInstance(instance, new Vec(0, 42, 1)).join();
        doNotHitEntity.setInstance(instance, new Vec(0, 42, 2)).join();

        var result = CollisionUtils.checkEntityCollisions(movingEntity, new Vec(0, 0, 1), 1.51, entity -> entity != movingEntity, null);

        assertEquals(1, result.size());
        assertEquals(stillEntity, result.iterator().next().entity());
    }

    @Test
    public void entityMultipleCollisionTest(Env env) {
        var instance = env.createFlatInstance();

        for (int i = -2; i <= 2; ++i)
            for (int j = -2; j <= 2; ++j)
                instance.loadChunk(i, j).join();

        var movingEntity = new Entity(EntityType.ZOMBIE);
        var stillEntity = new Entity(EntityType.ZOMBIE);
        var stillEntity2 = new Entity(EntityType.ZOMBIE);
        var doNotHitEntity = new Entity(EntityType.ZOMBIE);

        movingEntity.setInstance(instance, new Vec(0, 42, 0)).join();
        stillEntity.setInstance(instance, new Vec(0, 42, 1)).join();
        stillEntity2.setInstance(instance, new Vec(0, 42, 2)).join();
        doNotHitEntity.setInstance(instance, new Vec(0, 42, 3)).join();

        var result = CollisionUtils.checkEntityCollisions(movingEntity, new Vec(0, 0, 2), 1.51, entity -> entity != movingEntity, null);

        assertEquals(2, result.size());
    }

    @Test
    public void entityCollisionDetailsTest(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        var movingEntity = new Entity(EntityType.ZOMBIE);
        var stillEntity = new Entity(EntityType.ZOMBIE);
        movingEntity.setInstance(instance, new Vec(0, 42, 0)).join();
        stillEntity.setInstance(instance, new Vec(0, 42, 2)).join();

        var results = CollisionUtils.checkEntityCollisions(
                movingEntity, new Vec(0, 0, 4), 1.51, entity -> entity != movingEntity, null);

        assertEquals(1, results.size());
        final var result = results.getFirst();
        final double expectedPercentage = 0.35 * 0.99999;
        final var expectedPoint = new Vec(0, 42, 4 * expectedPercentage);
        assertEquals(expectedPercentage, result.percentage());
        assertTrue(expectedPoint.samePoint(result.collisionPoint()));
        // Moving along +Z it hits the north face of the other box
        assertEquals(new Vec(0, 0, -1), result.direction());
        assertEquals(stillEntity, result.entity());
    }

    @Test
    public void entityCollisionDirectionIsTheHitFace(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.loadChunk(-1, 0).join();

        var movingEntity = new Entity(EntityType.ZOMBIE);
        var westEntity = new Entity(EntityType.ZOMBIE);
        var belowEntity = new Entity(EntityType.ZOMBIE);
        movingEntity.setInstance(instance, new Vec(0, 45, 0)).join();
        westEntity.setInstance(instance, new Vec(-2, 45, 0)).join();
        belowEntity.setInstance(instance, new Vec(0, 42, 0)).join();

        var west = CollisionUtils.checkEntityCollisions(movingEntity, new Vec(-4, 0, 0), 1.51, entity -> entity == westEntity, null);
        assertEquals(1, west.size());
        assertEquals(new Vec(1, 0, 0), west.getFirst().direction());

        var down = CollisionUtils.checkEntityCollisions(movingEntity, new Vec(0, -4, 0), 1.51, entity -> entity == belowEntity, null);
        assertEquals(1, down.size());
        assertEquals(new Vec(0, 1, 0), down.getFirst().direction());
    }

    @Test
    public void entityCollisionsLeaveOutTheMovingEntityAndAreSorted(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        var movingEntity = new Entity(EntityType.ZOMBIE);
        movingEntity.setInstance(instance, new Vec(0, 42, 0.5)).join();
        // Registered farthest first
        var far = new Entity(EntityType.ZOMBIE);
        far.setInstance(instance, new Vec(0, 42, 2.5)).join();
        var near = new Entity(EntityType.ZOMBIE);
        near.setInstance(instance, new Vec(0, 42, 1.5)).join();
        var overlapping = new Entity(EntityType.ZOMBIE);
        overlapping.setInstance(instance, new Vec(0.2, 42, 0.5)).join();

        var results = CollisionUtils.checkEntityCollisions(movingEntity, new Vec(0, 0, 2.5), 1.51, _ -> true, null);

        assertEquals(List.of(overlapping, near, far), results.stream().map(EntityCollisionResult::entity).toList());
        assertEquals(0, results.getFirst().percentage());
        assertEquals(Vec.ZERO, results.getFirst().direction());
    }

    @Test
    public void entityCollisionAtBoxDiagonalTest(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        var movingEntity = new Entity(EntityType.ZOMBIE);
        var stillEntity = new Entity(EntityType.ZOMBIE);
        movingEntity.setInstance(instance, new Vec(8, 42, 8)).join();
        // 1.8 blocks up is inside the bottom center to top corner distance of a zombie box
        // (sqrt(1.95^2 + 0.3^2 + 0.3^2) ~= 1.996), the two boxes overlap.
        stillEntity.setInstance(instance, new Vec(8, 43.8, 8)).join();

        var result = CollisionUtils.checkEntityCollisions(movingEntity, Vec.ZERO, 0, entity -> entity != movingEntity, null);

        assertEquals(1, result.size());
        assertEquals(stillEntity, result.getFirst().entity());
    }
}
