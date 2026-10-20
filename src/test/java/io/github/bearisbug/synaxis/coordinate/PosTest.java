package io.github.bearisbug.synaxis.coordinate;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.github.bearisbug.synaxis.coordinate.Point.EPSILON;
import static io.github.bearisbug.synaxis.coordinate.Pos.VIEW_EPSILON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PosTest {

    @Test
    public void testConstructors() {
        // Test full constructor
        Pos pos1 = new Pos(1.5, 2.5, 3.5, 45f, 30f);
        assertEquals(1.5, pos1.x());
        assertEquals(2.5, pos1.y());
        assertEquals(3.5, pos1.z());
        assertEquals(45f, pos1.yaw());
        assertEquals(30f, pos1.pitch());

        // Test coordinate-only constructor
        Pos pos2 = new Pos(1.5, 2.5, 3.5);
        assertEquals(1.5, pos2.x());
        assertEquals(2.5, pos2.y());
        assertEquals(3.5, pos2.z());
        assertEquals(0f, pos2.yaw());
        assertEquals(0f, pos2.pitch());
    }

    @Test
    public void testYawPitchNormalization() {
        // Test yaw wrapping
        Pos pos1 = new Pos(0, 0, 0, 200f, 0f);
        assertEquals(-160f, pos1.yaw(), EPSILON);

        Pos pos2 = new Pos(0, 0, 0, -200f, 0f);
        assertEquals(160f, pos2.yaw(), EPSILON);

        Pos pos3 = new Pos(0, 0, 0, 720f, 0f);
        assertEquals(0f, pos3.yaw(), EPSILON);

        // Test pitch clamping
        Pos pos4 = new Pos(0, 0, 0, 0f, 100f);
        assertEquals(90f, pos4.pitch());

        Pos pos5 = new Pos(0, 0, 0, 0f, -100f);
        assertEquals(-90f, pos5.pitch());
    }

    @Test
    public void testFixYaw() {
        assertEquals(0f, Pos.fixYaw(0f), VIEW_EPSILON);
        assertEquals(90f, Pos.fixYaw(90f), VIEW_EPSILON);
        assertEquals(-90f, Pos.fixYaw(-90f), VIEW_EPSILON);
        assertEquals(180f, Pos.fixYaw(180f), VIEW_EPSILON);
        assertEquals(180f, Pos.fixYaw(-180f), VIEW_EPSILON);

        // Test wrapping
        assertEquals(-160f, Pos.fixYaw(200f), VIEW_EPSILON);
        assertEquals(160f, Pos.fixYaw(-200f), VIEW_EPSILON);
        assertEquals(0f, Pos.fixYaw(360f), VIEW_EPSILON);
        assertEquals(0f, Pos.fixYaw(720f), VIEW_EPSILON);
        assertEquals(85f, Pos.fixYaw(-1355f), VIEW_EPSILON);
        assertEquals(-135f, Pos.fixYaw(225f), VIEW_EPSILON);
    }

    @Test
    public void testFixPitch() {
        assertEquals(0f, Pos.fixPitch(0f));
        assertEquals(45f, Pos.fixPitch(45f));
        assertEquals(-45f, Pos.fixPitch(-45f));

        // Test clamping
        assertEquals(90f, Pos.fixPitch(90f));
        assertEquals(-90f, Pos.fixPitch(-90f));
        assertEquals(90f, Pos.fixPitch(100f));
        assertEquals(-90f, Pos.fixPitch(-100f));
        assertEquals(90f, Pos.fixPitch(225f));
        assertEquals(-90f, Pos.fixPitch(-135f));
    }

    @Test
    public void testWithCoord() {
        Pos base = new Pos(10, 20, 30, 45f, 30f);

        // Test with coordinates
        Pos modified = base.withCoord(15, 25, 35);
        assertEquals(15, modified.x());
        assertEquals(25, modified.y());
        assertEquals(35, modified.z());
        assertEquals(45f, modified.yaw());
        assertEquals(30f, modified.pitch());

        // Test with Point
        Vec point = new Vec(5, 10, 15);
        modified = base.withCoord(point);
        assertEquals(5, modified.x());
        assertEquals(10, modified.y());
        assertEquals(15, modified.z());
        assertEquals(45f, modified.yaw());
        assertEquals(30f, modified.pitch());
    }

    @Test
    public void testWithView() {
        Pos base = new Pos(10, 20, 30, 45f, 30f);

        // Test with yaw/pitch
        Pos modified = base.withView(90f, -45f);
        assertEquals(10, modified.x());
        assertEquals(20, modified.y());
        assertEquals(30, modified.z());
        assertEquals(90f, modified.yaw());
        assertEquals(-45f, modified.pitch());

        // Test with Pos
        Pos other = new Pos(0, 0, 0, 120f, -60f);
        modified = base.withView(other);
        assertEquals(10, modified.x());
        assertEquals(20, modified.y());
        assertEquals(30, modified.z());
        assertEquals(120f, modified.yaw());
        assertEquals(-60f, modified.pitch());
    }

    @Test
    public void testWithDirection() {
        Pos base = new Pos(0, 0, 0);

        // Look straight ahead (z positive)
        Pos pos = base.withDirection(new Vec(0, 0, 10));
        assertEquals(0f, pos.yaw(), 0.001f);
        assertEquals(0f, pos.pitch(), 0.001f);

        // Look to the right (x positive)
        pos = base.withDirection(new Vec(10, 0, 0));
        assertEquals(-90f, pos.yaw(), 0.001f);
        assertEquals(0f, pos.pitch(), 0.001f);

        // Look straight up
        pos = base.withDirection(new Vec(0, 10, 0));
        assertEquals(-90f, pos.pitch(), 0.001f);

        // Look straight down
        pos = base.withDirection(new Vec(0, -10, 0));
        assertEquals(90f, pos.pitch(), 0.001f);

        // A zero direction keeps the view
        Pos viewed = new Pos(0, 0, 0, 37f, 12f);
        assertEquals(viewed, viewed.withDirection(Vec.ZERO));

        // The direction is not a target point: the position does not change the view
        pos = new Pos(100, 0, 100).withDirection(new Vec(1, 0, 0));
        assertEquals(-90f, pos.yaw(), 0.001f);
        assertEquals(0f, pos.pitch(), 0.001f);
    }

    @Test
    public void testWithDirectionYaw() {
        Pos base = new Pos(0, 0, 0);
        assertAngle(-90, base.withDirection(new Vec(10, 0, 0)).yaw());
        assertAngle(0, base.withDirection(new Vec(0, 0, 10)).yaw());
        assertAngle(90, base.withDirection(new Vec(-10, 0, 0)).yaw());
        assertAngle(-180, base.withDirection(new Vec(1E-5, 0, -10)).yaw());
        assertAngle(180, base.withDirection(new Vec(-1E-5, 0, -10)).yaw());
        assertAngle(135, base.withDirection(new Vec(-5, 0, -5)).yaw());
        assertAngle(-45, base.withDirection(new Vec(5, 0, 5)).yaw());
    }

    @Test
    public void testWithDirectionPitch() {
        Pos base = new Pos(0, 0, 0);
        assertEquals(-90, base.withDirection(new Vec(0, 999999, 0)).pitch(), 1E-5);
        assertEquals(90, base.withDirection(new Vec(0, -999999, 0)).pitch(), 1E-5);
        assertEquals(0, base.withDirection(new Vec(-5, 0, 5)).pitch(), 1E-5);
        assertEquals(-45, base.withDirection(new Vec(5, 5, 0)).pitch(), 1E-5);
        // The horizontal distance is euclidean: 35.26° rather than 45° along a diagonal
        assertEquals(35.264, base.withDirection(new Vec(1, -1, 1)).pitch(), 0.001);
    }

    /**
     * Compares two yaws as angles, -180 and 180 being the same direction.
     */
    private static void assertAngle(double expected, float actual) {
        final double difference = Math.abs(((actual - expected) % 360 + 540) % 360 - 180);
        assertTrue(difference < 1E-4, () -> "expected the angle " + expected + " but was " + actual);
    }

    @Test
    public void testWithYaw() {
        Pos base = new Pos(10, 20, 30, 45f, 30f);

        // Test with value
        Pos modified = base.withYaw(90f);
        assertEquals(10, modified.x());
        assertEquals(20, modified.y());
        assertEquals(30, modified.z());
        assertEquals(90f, modified.yaw());
        assertEquals(30f, modified.pitch());

        // Test with operator
        modified = base.withYaw(yaw -> yaw + 45f);
        assertEquals(90f, modified.yaw());
        assertEquals(30f, modified.pitch());
    }

    @Test
    public void testWithPitch() {
        Pos base = new Pos(10, 20, 30, 45f, 30f);

        // Test with value
        Pos modified = base.withPitch(-45f);
        assertEquals(10, modified.x());
        assertEquals(20, modified.y());
        assertEquals(30, modified.z());
        assertEquals(45f, modified.yaw());
        assertEquals(-45f, modified.pitch());

        // Test with operator
        modified = base.withPitch(pitch -> pitch + 15f);
        assertEquals(45f, modified.yaw());
        assertEquals(45f, modified.pitch());
    }

    @Test
    public void testSameView() {
        Pos pos1 = new Pos(0, 0, 0, 45f, 30f);
        Pos pos2 = new Pos(10, 20, 30, 45f, 30f);
        Pos pos3 = new Pos(0, 0, 0, 46f, 30f);

        // Test with Pos
        assertTrue(pos1.sameView(pos2));
        assertFalse(pos1.sameView(pos3));

        // Test with values
        assertTrue(pos1.sameView(45f, 30f));
        assertFalse(pos1.sameView(46f, 30f));
        assertFalse(pos1.sameView(45f, 31f));
    }

    @Test
    public void testSimilarView() {
        Pos pos1 = new Pos(0, 0, 0, 45f, 30f);
        Pos pos2 = new Pos(0, 0, 0, 45.0001f, 30.0001f);
        Pos pos3 = new Pos(0, 0, 0, 46f, 31f);

        // Test with default epsilon
        assertTrue(pos1.similarView(pos2));
        assertFalse(pos1.similarView(pos3));

        // Test with custom epsilon
        assertTrue(pos1.similarView(pos3, 2f));
        assertFalse(pos1.similarView(pos3, 0.5f));

        // Test with yaw/pitch values
        assertTrue(pos1.similarView(45.0001f, 30.0001f));
        assertTrue(pos1.similarView(45.0001f, 30.0001f, VIEW_EPSILON));
        assertFalse(pos1.similarView(46f, 31f, 0.5f));
    }

    @Test
    public void testDirection() {
        // Facing south (default)
        Pos pos1 = new Pos(0, 0, 0, 0f, 0f);
        Vec dir1 = pos1.direction();
        assertEquals(0, dir1.x(), 0.0001);
        assertEquals(0, dir1.y(), 0.0001);
        assertEquals(1, dir1.z(), 0.0001);

        // Facing east
        Pos pos2 = new Pos(0, 0, 0, -90f, 0f);
        Vec dir2 = pos2.direction();
        assertEquals(1, dir2.x(), 0.0001);
        assertEquals(0, dir2.y(), 0.0001);
        assertEquals(0, dir2.z(), 0.0001);

        // Facing down
        Pos pos3 = new Pos(0, 0, 0, 0f, 90f);
        Vec dir3 = pos3.direction();
        assertEquals(0, dir3.x(), 0.0001);
        assertEquals(-1, dir3.y(), 0.0001);
        assertEquals(0, dir3.z(), 0.0001);

        // Direction should be unit vector
        assertTrue(dir1.isNormalized());
        assertTrue(dir2.isNormalized());
        assertTrue(dir3.isNormalized());
    }

    @Test
    public void testLerpView() {
        Pos start = new Pos(0, 0, 0, 0f, 0f);
        Pos end = new Pos(0, 0, 0, 90f, 45f);

        // Halfway
        Pos mid = start.lerpView(end, 0.5f);
        assertEquals(0, mid.x());
        assertEquals(0, mid.y());
        assertEquals(0, mid.z());
        assertEquals(45f, mid.yaw(), VIEW_EPSILON);
        assertEquals(22.5f, mid.pitch(), VIEW_EPSILON);

        // At start
        Pos atStart = start.lerpView(end, 0);
        assertEquals(0f, atStart.yaw());
        assertEquals(0f, atStart.pitch());

        // At end
        Pos atEnd = start.lerpView(end, 1);
        assertEquals(90f, atEnd.yaw());
        assertEquals(45f, atEnd.pitch());
    }

    @Test
    public void testNegView() {
        Pos pos = new Pos(10, 20, 30, 45f, 30f);
        Pos negated = pos.negView();

        assertEquals(10, negated.x());
        assertEquals(20, negated.y());
        assertEquals(30, negated.z());
        assertEquals(-45f, negated.yaw());
        assertEquals(-30f, negated.pitch());
    }

    @Test
    public void testAbsView() {
        Pos pos = new Pos(10, 20, 30, -45f, -30f);
        Pos absolute = pos.absView();

        assertEquals(10, absolute.x());
        assertEquals(20, absolute.y());
        assertEquals(30, absolute.z());
        assertEquals(45f, absolute.yaw());
        assertEquals(30f, absolute.pitch());
    }

    @Test
    public void testApply() {
        Pos pos = new Pos(10, 20, 30, 45f, 30f);

        // Test operator
        Pos result = pos.apply((x, y, z, yaw, pitch) ->
            new Pos(x * 2, y * 2, z * 2, yaw + 45f, pitch + 15f));

        assertEquals(20, result.x());
        assertEquals(40, result.y());
        assertEquals(60, result.z());
        assertEquals(90f, result.yaw());
        assertEquals(45f, result.pitch());
    }

    @Test
    public void testAsPos() {
        Point pos = new Pos(1, 2, 3, 45f, 30f);
        assertSame(pos, pos.asPos());
    }

    @Test
    public void testAsPosWithView() {
        Pos pos = new Pos(1, 2, 3, 45f, 30f);
        Pos result = pos.withView(90f, 60f);
        assertEquals(1, result.x());
        assertEquals(2, result.y());
        assertEquals(3, result.z());
        assertEquals(90f, result.yaw());
        assertEquals(60f, result.pitch());
    }

    @Test
    public void testArithmeticPreservesView() {
        Pos base = new Pos(10, 20, 30, 45f, 30f);

        // Test add
        Pos added = base.add(5, 10, 15);
        assertEquals(15, added.x());
        assertEquals(30, added.y());
        assertEquals(45, added.z());
        assertEquals(45f, added.yaw());
        assertEquals(30f, added.pitch());

        // Test sub
        Pos subtracted = base.sub(5, 10, 15);
        assertEquals(5, subtracted.x());
        assertEquals(10, subtracted.y());
        assertEquals(15, subtracted.z());
        assertEquals(45f, subtracted.yaw());
        assertEquals(30f, subtracted.pitch());

        // Test mul
        Pos multiplied = base.mul(2);
        assertEquals(20, multiplied.x());
        assertEquals(40, multiplied.y());
        assertEquals(60, multiplied.z());
        assertEquals(45f, multiplied.yaw());
        assertEquals(30f, multiplied.pitch());

        // Test div
        Pos divided = base.div(2);
        assertEquals(5, divided.x());
        assertEquals(10, divided.y());
        assertEquals(15, divided.z());
        assertEquals(45f, divided.yaw());
        assertEquals(30f, divided.pitch());
    }

    @Test
    public void testTransformationsPreserveView() {
        Pos base = new Pos(10, 20, 30, 45f, 30f);

        // Test neg
        Pos negated = base.neg();
        assertEquals(-10, negated.x());
        assertEquals(-20, negated.y());
        assertEquals(-30, negated.z());
        assertEquals(45f, negated.yaw());
        assertEquals(30f, negated.pitch());

        // Test abs
        Pos absolute = negated.abs();
        assertEquals(10, absolute.x());
        assertEquals(20, absolute.y());
        assertEquals(30, absolute.z());
        assertEquals(45f, absolute.yaw());
        assertEquals(30f, absolute.pitch());

        // Test normalize
        Pos normalized = base.normalize();
        assertTrue(normalized.isNormalized());
        assertEquals(45f, normalized.yaw());
        assertEquals(30f, normalized.pitch());
        // A zero vector normalizes to itself and keeps the view
        assertEquals(new Pos(0, 0, 0, 45f, 30f), new Pos(0, 0, 0, 45f, 30f).normalize());

        // Test cross
        Pos other = new Pos(1, 0, 0);
        Pos crossed = base.cross(other);
        assertEquals(45f, crossed.yaw());
        assertEquals(30f, crossed.pitch());

        // Test lerp
        Pos end = new Pos(20, 40, 60);
        Pos lerped = base.lerp(end, 0.5);
        assertEquals(45f, lerped.yaw());
        assertEquals(30f, lerped.pitch());
    }

    @Test
    public void testRandomPositions() {
        Random random = new Random(98765L);

        for (int i = 0; i < 100; i++) {
            double x = random.nextDouble() * 200 - 100;
            double y = random.nextDouble() * 200 - 100;
            double z = random.nextDouble() * 200 - 100;
            float yaw = random.nextFloat() * 720 - 360;
            float pitch = random.nextFloat() * 200 - 100;

            Pos pos = new Pos(x, y, z, yaw, pitch);
            assertEquals(x, pos.x(), 0.0001);
            assertEquals(y, pos.y(), 0.0001);
            assertEquals(z, pos.z(), 0.0001);

            // Verify yaw is normalized
            assertTrue(pos.yaw() >= -180f && pos.yaw() <= 180f);
            // Verify pitch is clamped
            assertTrue(pos.pitch() >= -90f && pos.pitch() <= 90f);

            // Test immutability
            Pos original = pos;
            Pos modified = pos.withCoord(0, 0, 0);
            assertEquals(x, original.x(), 0.0001);
            assertEquals(0, modified.x(), 0.0001);
        }
    }

    @Test
    public void testMinMaxPreserveView() {
        Pos pos1 = new Pos(10, 20, 30, 45f, 30f);
        Pos pos2 = new Pos(15, 5, 25);

        // Test min
        Pos min = pos1.min(pos2);
        assertEquals(10, min.x());
        assertEquals(5, min.y());
        assertEquals(25, min.z());
        assertEquals(45f, min.yaw());
        assertEquals(30f, min.pitch());

        // Test max
        Pos max = pos1.max(pos2);
        assertEquals(15, max.x());
        assertEquals(20, max.y());
        assertEquals(30, max.z());
        assertEquals(45f, max.yaw());
        assertEquals(30f, max.pitch());
    }

    @Test
    public void testViewEdgeCases() {
        // Test exact boundaries
        Pos pos1 = new Pos(0, 0, 0, 180f, 90f);
        assertEquals(180f, pos1.yaw());
        assertEquals(90f, pos1.pitch());

        Pos pos2 = new Pos(0, 0, 0, -180f, -90f);
        assertEquals(180f, pos2.yaw());
        assertEquals(-90f, pos2.pitch());

        // Test just outside boundaries
        Pos pos3 = new Pos(0, 0, 0, 180.1f, 90.1f);
        assertTrue(pos3.yaw() >= -180f && pos3.yaw() <= 180f);
        assertEquals(90f, pos3.pitch());

        Pos pos4 = new Pos(0, 0, 0, -180.1f, -90.1f);
        assertTrue(pos4.yaw() >= -180f && pos4.yaw() <= 180f);
        assertEquals(-90f, pos4.pitch());
    }

    @Test
    public void testFixYawOfLargeValues() {
        for (float yaw : new float[]{1e12f, -3e38f, Float.MAX_VALUE, 1.5e8f}) {
            final float fixed = Pos.fixYaw(yaw);
            assertTrue(fixed > -180f && fixed <= 180f, yaw + " fixed to " + fixed);
            assertEquals(fixed, Pos.fixYaw(fixed), yaw + " fixed to " + fixed + " twice");
        }
        // The remainder is exact: 150,000,000 = 416,667 * 360 - 120
        assertEquals(-120f, Pos.fixYaw(1.5e8f));
        // Already in range, the yaw is kept as it is
        assertEquals(Math.nextUp(-180f), Pos.fixYaw(Math.nextUp(-180f)));
        for (float yaw : new float[]{-0f, -360f, -720f}) {
            assertEquals(Float.floatToRawIntBits(0f), Float.floatToRawIntBits(Pos.fixYaw(yaw)), "the sign of zero from " + yaw);
        }
        assertEquals(new Pos(0, 0, 0), new Pos(0, 0, 0, -360f, 0));
        assertTrue(Float.isNaN(Pos.fixYaw(Float.NaN)));
        assertTrue(Float.isNaN(Pos.fixYaw(Float.POSITIVE_INFINITY)));
    }

    @Test
    public void testViewAcrossTheYawSeam() {
        // 170 and -170 are 20 degrees apart through 180, the view turns that way
        assertEquals(180f, new Pos(0, 0, 0, 170f, 0f).lerpView(new Pos(0, 0, 0, -170f, 0f), 0.5f).yaw(), VIEW_EPSILON);
        assertTrue(new Pos(0, 0, 0, 179.99f, 0f).similarView(-179.99f, 0f, 0.1f));
        assertFalse(new Pos(0, 0, 0, 179.99f, 0f).similarView(-170f, 0f, 0.1f));
    }
}

