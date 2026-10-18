package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PhysicsUtilsTest {
    private static final Aerodynamics AIR = new Aerodynamics(0.08, 0.91, 0.98);
    private static final Block.Getter AIR_BLOCKS = (_, _, _, _) -> Block.AIR;
    private static final Pos POSITION = new Pos(0, 42, 0);
    private static final Vec VELOCITY = new Vec(1, 0.5, -1);

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
}
