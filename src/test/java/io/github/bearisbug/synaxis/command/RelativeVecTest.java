package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a parsed coordinate lands: relative components add the origin, local ones go along its facing, absolute ones
 * stay where they are; a view adds the yaw and the pitch.
 */
public class RelativeVecTest {
    private static final Pos ORIGIN = new Pos(10, 64, -5, 90, 0);

    @Test
    public void aCoordinateLandsByItsType() {
        var relative = new RelativeVec(new Vec(1, 2, 3), RelativeVec.CoordinateType.RELATIVE, true, false, true);
        assertEquals(new Vec(11, 2, -2), relative.from(ORIGIN));
        assertEquals(new Vec(1, 2, 3), relative.from((Pos) null));
        assertEquals(new Vec(1, 2, 3), relative.from((Entity) null));
        assertEquals(new Vec(1, 2, 3), relative.fromSender(null));

        var absolute = new RelativeVec(new Vec(1, 2, 3), RelativeVec.CoordinateType.ABSOLUTE, false, false, false);
        assertEquals(new Vec(1, 2, 3), absolute.from(ORIGIN));

        // ^ ^ ^2 is two blocks ahead, and a yaw of 90 faces -x
        var local = new RelativeVec(new Vec(0, 0, 2), RelativeVec.CoordinateType.LOCAL, true, true, true);
        final Vec ahead = local.from(ORIGIN);
        assertEquals(8, ahead.x(), 1e-9);
        assertEquals(64, ahead.y(), 1e-9);
        assertEquals(-5, ahead.z(), 1e-9);
    }

    @Test
    public void aViewAddsTheYawAndThePitch() {
        var view = new RelativeVec(new Vec(10, 0, 5), RelativeVec.CoordinateType.RELATIVE, true, false, true);
        assertEquals(new Vec(100, 0, 35), view.fromView(new Pos(0, 0, 0, 90, 30)));
        assertEquals(new Vec(10, 0, 5), view.fromView((Pos) null));

        var absolute = new RelativeVec(new Vec(10, 0, 5), RelativeVec.CoordinateType.ABSOLUTE, false, false, false);
        assertEquals(new Vec(10, 0, 5), absolute.fromView(new Pos(0, 0, 0, 90, 30)));
    }
}
