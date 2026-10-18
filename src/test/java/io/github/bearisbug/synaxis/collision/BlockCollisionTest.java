package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlockCollisionTest {
    private static final BoundingBox ZOMBIE = new BoundingBox(0.6, 1.95, 0.6);
    private static final Pos START = new Pos(0.5, 42, 0.5, 30f, 10f);

    /**
     * Air everywhere, refusing to answer after enough calls that a sweep has run away.
     */
    private static final class CountingAir implements Block.Getter {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Block getBlock(int x, int y, int z, Condition condition) {
            if (calls.incrementAndGet() > 100_000) throw new IllegalStateException("the sweep does not end");
            return Block.AIR;
        }
    }

    @Test
    public void nonFiniteVelocityLeavesTheBoxWhereItIs() {
        for (Vec velocity : new Vec[]{
                new Vec(Double.POSITIVE_INFINITY, 0, 0),
                new Vec(0, Double.NEGATIVE_INFINITY, 0),
                new Vec(0, 0, Double.NaN),
                new Vec(Double.NaN, Double.NaN, Double.NaN)}) {
            final CountingAir air = new CountingAir();
            final PhysicsResult result = CollisionUtils.handlePhysics(air, ZOMBIE, START, velocity, null, false);
            assertEquals(START, result.newPosition(), () -> "velocity " + velocity);
            assertEquals(Vec.ZERO, result.newVelocity(), () -> "velocity " + velocity);
            assertEquals(0, air.calls.get(), () -> "velocity " + velocity);

            final PhysicsResult bordered = CollisionUtils.handlePhysics(air,
                    WorldBorder.defaultBorder().withDiameter(64), ZOMBIE, START, velocity, null, false);
            assertEquals(START, bordered.newPosition(), () -> "velocity " + velocity);
        }
    }

    @Test
    public void finiteVelocityStillMoves() {
        final PhysicsResult result = CollisionUtils.handlePhysics(new CountingAir(), ZOMBIE, START, new Vec(20, 0, 20), null, false);
        assertTrue(result.newPosition().samePoint(START.add(20, 0, 20), 1e-3), () -> "position " + result.newPosition());
    }

    @Test
    public void invertedBoundingBoxIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(-2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(1, -0.5, 1));
        assertThrows(IllegalArgumentException.class, () -> ZOMBIE.contract(2, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(new Vec(0, Double.NaN, 0), Vec.ONE));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(Vec.ONE, Vec.ZERO));
        // A box without volume is a valid one
        assertEquals(0, new BoundingBox(0, 0, 0).width());
    }
}
