package io.github.bearisbug.synaxis.instance.block;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This class performs ray tracing and iterates along blocks on a line
 */
public class BlockIterator implements Iterator<Point> {
    private final short[] signums = new short[3];
    private @Nullable Vec end;
    private boolean smooth;

    private boolean foundEnd = false;

    // length of the ray from the current position to the next x, y or z grid plane
    private double sideDistX;
    private double sideDistY;
    private double sideDistZ;

    // length of the ray between two neighbouring grid planes of the same axis
    private double deltaDistX;
    private double deltaDistY;
    private double deltaDistZ;

    // coordinates of the block the ray is in
    private int mapX;
    private int mapY;
    private int mapZ;

    private final ArrayDeque<Point> extraPoints = new ArrayDeque<>();

    /**
     * Constructs the BlockIterator.
     * <p>
     * This considers all blocks as 1x1x1 in size.
     *
     * @param start       A Vector giving the initial position for the trace
     * @param direction   A Vector pointing in the direction for the trace
     * @param yOffset     The trace begins vertically offset from the start vector
     *                    by this value
     * @param smooth      A boolean indicating whether the cast should be smooth.
     *                    Smooth casts will only include one block when intersecting multiple axis lines.
     * @param maxDistance This is the maximum distance in blocks for the
     *                    trace, finite and not negative. A value of 0 indicates no limit:
     *                    the iterator never ends, the caller has to stop it
     * @throws IllegalArgumentException if {@code maxDistance} is negative or not finite
     */
    @SuppressWarnings("this-escape") // constructor calls overridable reset(); subclasses must not rely on their own fields in reset()
    public BlockIterator(Vec start, Vec direction, double yOffset, double maxDistance, boolean smooth) {
        reset(start, direction, yOffset, maxDistance, smooth);
    }

    /**
     * Constructs a BlockIterator without a trace: call
     * {@link #reset(Vec, Vec, double, double, boolean)} before {@link #next()}.
     */
    public BlockIterator() {}

    /**
     * Starts a new trace, see {@link #BlockIterator(Vec, Vec, double, double, boolean)}.
     *
     * @param start       A Vector giving the initial position for the trace
     * @param direction   A Vector pointing in the direction for the trace
     * @param yOffset     The trace begins vertically offset from the start vector by this value
     * @param maxDistance This is the maximum distance in blocks for the trace, finite and not negative.
     *                    A value of 0 indicates no limit: the iterator never ends, the caller has to stop it
     * @param smooth      A boolean indicating whether the cast should be smooth
     * @throws IllegalArgumentException if {@code maxDistance} is negative or not finite
     */
    public void reset(Vec start, Vec direction, double yOffset, double maxDistance, boolean smooth) {
        // Negative, the end lies behind the ray and is never reached; not finite, there is none
        Check.argCondition(!Double.isFinite(maxDistance) || maxDistance < 0,
                "maxDistance must be finite and >= 0 (0 = unbounded), got: {0}", maxDistance);
        extraPoints.clear();
        foundEnd = false;

        start = start.add(0, yOffset, 0);

        if (maxDistance != 0) end = start.add(direction.normalize().mul(maxDistance));
        else end = null;

        if (direction.isZero()) this.foundEnd = true;

        this.smooth = smooth;

        Vec ray = direction.normalize();

        //which box of the map we're in
        mapX = start.blockX();
        mapY = start.blockY();
        mapZ = start.blockZ();

        signums[0] = (short) Math.signum(direction.x());
        signums[1] = (short) Math.signum(direction.y());
        signums[2] = (short) Math.signum(direction.z());

        // Find grid intersections for x, y, z. This works by calculating and storing the distance to the next grid
        // intersection on the x, y and z axis
        deltaDistX = (ray.x() == 0) ? 1e30 : Math.abs(1 / ray.x());
        deltaDistY = (ray.y() == 0) ? 1e30 : Math.abs(1 / ray.y());
        deltaDistZ = (ray.z() == 0) ? 1e30 : Math.abs(1 / ray.z());

        //calculate step and initial sideDist
        if (ray.x() < 0) sideDistX = (start.x() - mapX) * deltaDistX;
        else if (ray.x() > 0) sideDistX = (mapX + signums[0] - start.x()) * deltaDistX;
        else sideDistX = Double.MAX_VALUE;

        if (ray.y() < 0) sideDistY = (start.y() - mapY) * deltaDistY;
        else if (ray.y() > 0) sideDistY = (mapY + signums[1] - start.y()) * deltaDistY;
        else sideDistY = Double.MAX_VALUE;

        if (ray.z() < 0) sideDistZ = (start.z() - mapZ) * deltaDistZ;
        else if (ray.z() > 0) sideDistZ = (mapZ + signums[2] - start.z()) * deltaDistZ;
        else sideDistZ = Double.MAX_VALUE;
    }

    /**
     * Constructs the BlockIterator.
     * <p>
     * This considers all blocks as 1x1x1 in size.
     *
     * @param start       A Vector giving the initial position for the trace
     * @param direction   A Vector pointing in the direction for the trace
     * @param yOffset     The trace begins vertically offset from the start vector
     *                    by this value
     * @param maxDistance This is the maximum distance in blocks for the
     *                    trace, finite and not negative. A value of 0 indicates no limit:
     *                    the iterator never ends, the caller has to stop it
     * @throws IllegalArgumentException if {@code maxDistance} is negative or not finite
     */
    public BlockIterator(Vec start, Vec direction, double yOffset, double maxDistance) {
        this(start, direction, yOffset, maxDistance, false);
    }

    /**
     * Constructs the BlockIterator.
     * <p>
     * This considers all blocks as 1x1x1 in size.
     *
     * @param pos         The position for the start of the ray trace
     * @param yOffset     The trace begins vertically offset from the start vector
     *                    by this value
     * @param maxDistance This is the maximum distance in blocks for the
     *                    trace, finite and not negative. A value of 0 indicates no limit:
     *                    the iterator never ends, the caller has to stop it
     * @throws IllegalArgumentException if {@code maxDistance} is negative or not finite
     */

    public BlockIterator(Pos pos, double yOffset, int maxDistance) {
        this(pos.asVec(), pos.direction(), yOffset, maxDistance, false);
    }

    /**
     * Constructs the BlockIterator, without a limit: the iterator never ends, the caller has to stop it.
     * <p>
     * This considers all blocks as 1x1x1 in size.
     *
     * @param pos     The position for the start of the ray trace
     * @param yOffset The trace begins vertically offset from the start vector
     *                by this value
     */

    public BlockIterator(Pos pos, double yOffset) {
        this(pos.asVec(), pos.direction(), yOffset, 0, false);
    }

    /**
     * Constructs the BlockIterator, without a limit: the iterator never ends, the caller has to stop it.
     * <p>
     * This considers all blocks as 1x1x1 in size.
     *
     * @param pos The position for the start of the ray trace
     */

    public BlockIterator(Pos pos) {
        this(pos, 0f);
    }

    /**
     * Constructs the BlockIterator.
     * <p>
     * This considers all blocks as 1x1x1 in size.
     *
     * @param entity      Information from the entity is used to set up the trace
     * @param maxDistance This is the maximum distance in blocks for the
     *                    trace, finite and not negative. A value of 0 indicates no limit:
     *                    the iterator never ends, the caller has to stop it
     * @throws IllegalArgumentException if {@code maxDistance} is negative or not finite
     */

    public BlockIterator(Entity entity, int maxDistance) {
        this(entity.getPosition(), entity.getEyeHeight(), maxDistance);
    }

    /**
     * Constructs the BlockIterator, without a limit: the iterator never ends, the caller has to stop it.
     * <p>
     * This considers all blocks as 1x1x1 in size.
     *
     * @param entity Information from the entity is used to set up the trace
     */

    public BlockIterator(Entity entity) {
        this(entity, 0);
    }

    /**
     * Returns true if the iteration has more elements
     */

    @Override
    public boolean hasNext() {
        return !foundEnd;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("[BlockIterator] doesn't support block removal");
    }

    /**
     * Returns the next BlockPosition in the trace
     *
     * @return the next BlockPosition in the trace
     */

    @Override
    public Point next() {
        if (foundEnd) throw new NoSuchElementException();
        if (!extraPoints.isEmpty()) {
            var res = extraPoints.poll();
            if (end != null && res.sameBlock(end)) foundEnd = true;
            return res;
        }

        var current = new Vec(mapX, mapY, mapZ);
        if (end != null && current.sameBlock(end)) foundEnd = true;

        double closest = Math.min(sideDistX, Math.min(sideDistY, sideDistZ));
        boolean needsX = sideDistX - closest < 1e-10 && signums[0] != 0;
        boolean needsY = sideDistY - closest < 1e-10 && signums[1] != 0;
        boolean needsZ = sideDistZ - closest < 1e-10 && signums[2] != 0;

        if (needsZ) {
            sideDistZ += deltaDistZ;
            mapZ += signums[2];
        }

        if (needsX) {
            sideDistX += deltaDistX;
            mapX += signums[0];
        }

        if (needsY) {
            sideDistY += deltaDistY;
            mapY += signums[1];
        }

        if (needsX && needsY && needsZ) {
            extraPoints.add(new Vec(signums[0] + current.x(), signums[1] + current.y(), current.z()));
            if (smooth) return current;

            extraPoints.add(new Vec(current.x(), signums[1] + current.y(), signums[2] + current.z()));
            extraPoints.add(new Vec(signums[0] + current.x(), current.y(), signums[2] + current.z()));

            extraPoints.add(new Vec(signums[0] + current.x(), current.y(), current.z()));
            extraPoints.add(new Vec(current.x(), signums[1] + current.y(), current.z()));
            extraPoints.add(new Vec(current.x(), current.y(), signums[2] + current.z()));
        } else if (needsX && needsY) {
            extraPoints.add(new Vec(signums[0] + current.x(), current.y(), current.z()));
            if (smooth) return current;
            extraPoints.add(new Vec(current.x(), signums[1] + current.y(), current.z()));
        } else if (needsX && needsZ) {
            extraPoints.add(new Vec(signums[0] + current.x(), current.y(), current.z()));
            if (smooth) return current;
            extraPoints.add(new Vec(current.x(), current.y(), signums[2] + current.z()));
        } else if (needsY && needsZ) {
            extraPoints.add(new Vec(current.x(), signums[1] + current.y(), current.z()));
            if (smooth) return current;
            extraPoints.add(new Vec(current.x(), current.y(), signums[2] + current.z()));
        }

        return current;
    }
}
