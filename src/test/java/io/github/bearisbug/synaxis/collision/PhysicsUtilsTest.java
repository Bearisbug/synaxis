package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PhysicsUtilsTest {
    private static final Aerodynamics AIR = new Aerodynamics(0.08, 0.91, 0.98);
    private static final Block.Getter AIR_BLOCKS = (_, _, _, _) -> Block.AIR;
    private static final Pos POSITION = new Pos(0, 42, 0);
    private static final Vec VELOCITY = new Vec(1, 0.5, -1);
    private static final BoundingBox BOX = new BoundingBox(0.6, 1.8, 0.6);

    @Test
    public void anUnmovedEntityLosesItsHorizontalVelocity() {
        assertEquals(new Vec(0, -0.08 * 0.98, 0),
                PhysicsUtils.updateVelocity(POSITION, VELOCITY, AIR_BLOCKS, AIR, false, false, false, false));
        assertEquals(Vec.ZERO,
                PhysicsUtils.updateVelocity(POSITION, VELOCITY, AIR_BLOCKS, AIR, false, false, false, true));
        assertEquals(Vec.ZERO,
                PhysicsUtils.updateVelocity(POSITION, VELOCITY, AIR_BLOCKS, AIR, false, true, false, false));
    }

    @Test
    public void aFlyingEntityWithoutGravityKeepsItsVerticalVelocity() {
        assertEquals(new Vec(0.91, 0.5, -0.91),
                PhysicsUtils.updateVelocity(POSITION, VELOCITY, AIR_BLOCKS, AIR, true, true, false, true));
        // With gravity a flying entity only drags the vertical velocity
        assertEquals(new Vec(0.91, 0.5 * 0.6, -0.91),
                PhysicsUtils.updateVelocity(POSITION, VELOCITY, AIR_BLOCKS, AIR, true, true, false, false));
    }

    @Test
    public void aResultWithANewVelocityKeepsTheRest() {
        final Block.Getter floor = (_, y, _, _) -> y < 42 ? Block.STONE : Block.AIR;
        final PhysicsResult landed = CollisionUtils.handlePhysics(floor, BOX, new Pos(0.5, 42.5, 0.5),
                new Vec(0.2, -1, 0), null, false);
        assertTrue(landed.isOnGround());
        assertTrue(landed.hasCollision());

        final PhysicsResult changed = landed.withNewVelocity(new Vec(1, 2, 3));
        assertEquals(new Vec(1, 2, 3), changed.newVelocity());
        assertEquals(landed, changed.withNewVelocity(landed.newVelocity()));
    }

    @Test
    public void onlyALandingIsOnTheGround() {
        final Block.Getter ceiling = (_, y, _, _) -> y >= 45 ? Block.STONE : Block.AIR;
        final PhysicsResult bumped = CollisionUtils.handlePhysics(ceiling, BOX, new Pos(0.5, 43, 0.5),
                new Vec(0, 1, 0), null, false);
        assertTrue(bumped.collisionY());
        assertTrue(bumped.hasCollision());
        assertFalse(bumped.isOnGround());

        final PhysicsResult free = CollisionUtils.handlePhysics(AIR_BLOCKS, BOX, POSITION, VELOCITY, null, false);
        assertFalse(free.hasCollision());
        assertFalse(free.isOnGround());
    }
}
