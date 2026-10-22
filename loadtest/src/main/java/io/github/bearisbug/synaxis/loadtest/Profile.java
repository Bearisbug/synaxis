package io.github.bearisbug.synaxis.loadtest;

import java.util.List;
import java.util.Locale;

/**
 * How a bot moves ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}, speeds from
 * {@code docs/DESIGN-M3.md} §2): walking 4.317 blocks a second, riding 10, flying 30, with a
 * position packet every tick.
 * <p>
 * A bot starts where the server spawned it and goes round a circle through that point, each bot
 * its own direction and radius. The ground it covers is bounded, so the server's loaded chunks stay
 * bounded over a run of any length, while the chunks entering the bot's view keep coming as they
 * would on a straight line: the circle is at least eight chunks across, wider than a view.
 * <p>
 * {@link #TOWN} is one exception: the crowd of a town square ({@code docs/DESIGN-PROD.md} §15, the
 * visible entities row), every bot walking a circle of 4 to 32 blocks, so that they all stay within
 * 64 blocks of the spawn and in sight of each other.
 * <p>
 * {@link #GATE} is the other: no circle, but a walk at 10 blocks a second into the nearest gate
 * frame of {@code --gates}, to be sent to another shard ({@code docs/DESIGN-M3.md} §15, the transfer
 * row). Where it goes depends on whether a Transfer came, see {@link GateWalk}.
 */
enum Profile {
    WALK(4.317, 64, 192, 0),
    RIDE(10.0, 128, 384, 0),
    FLY(30.0, 256, 768, 24),
    TOWN(4.317, 4, 32, 0),
    GATE(10.0, 0, 0, 0);

    /** Blocks per second. */
    final double speed;
    /** The radii of the circles, spread over the bots. */
    final double minRadius, maxRadius;
    /** Height above the spawn a flying bot keeps, 0 on the ground. */
    final double lift;

    Profile(double speed, double minRadius, double maxRadius, double lift) {
        this.speed = speed;
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.lift = lift;
    }

    static Profile parse(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--profile is walk, ride, fly, town or gate, not " + value, e);
        }
    }

    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Where bot {@code index} is {@code seconds} after it started moving from {@code (x0, y0, z0)};
     * not for {@link #GATE}, which walks a {@link GateWalk}.
     *
     * @return x, y, z
     */
    double[] position(int index, double x0, double y0, double z0, double seconds) {
        // The golden angle spreads the directions evenly whatever the count
        final double direction = index * 2.399963229728653;
        final double radius = minRadius + (maxRadius - minRadius) * ((index * 0.6180339887498949) % 1.0);
        final double centerX = x0 + radius * Math.cos(direction);
        final double centerZ = z0 + radius * Math.sin(direction);
        // Starts on the spawn: the angle from the center back to it is direction + pi
        final double angle = direction + Math.PI + seconds * speed / radius;
        final double climb = Math.min(lift, seconds * speed);
        return new double[]{centerX + radius * Math.cos(angle), y0 + climb, centerZ + radius * Math.sin(angle)};
    }

    /**
     * A gate frame of {@code --gates}, the box of a gate in the shard directory
     * ({@code docs/DESIGN-M3.md} {@code API-M3-010}): block coordinates, both corners included.
     */
    record Frame(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        Frame {
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("a gate frame from " + minX + "," + minY + "," + minZ
                        + " to " + maxX + "," + maxY + "," + maxZ + " has its min above its max");
        }

        /**
         * Whether a position is in the frame: the block it is in, every coordinate rounded down, is one
         * of the frame's. The rule of the shard's {@code ShardGates}: x = 2031.99 is outside a frame
         * starting at 2032, x = 2032.0 inside.
         */
        boolean contains(double x, double y, double z) {
            return within(x, minX, maxX) && within(y, minY, maxY) && within(z, minZ, maxZ);
        }

        private static boolean within(double coordinate, int min, int max) {
            final double block = Math.floor(coordinate);
            return block >= min && block <= max;
        }

        /** How far a position is from the space the frame's blocks fill, 0 inside it. */
        double distance(double x, double y, double z) {
            final double dx = outside(x, minX, maxX);
            final double dy = outside(y, minY, maxY);
            final double dz = outside(z, minZ, maxZ);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static double outside(double coordinate, int min, int max) {
            return Math.max(0, Math.max(min - coordinate, coordinate - (max + 1.0)));
        }
    }

    /**
     * The walk of a {@link #GATE} bot from one spawn ({@code docs/DESIGN-M3.md} §26 M3-E track D). It
     * stands at the spawn for the dwell, then walks at 10 blocks a second straight to the centre of
     * the nearest frame and stays there. The centre is taken at the bot's own height when that is
     * within the frame, so that a frame as tall as the world is walked into level, else at the
     * frame's middle height.
     * <p>
     * The first position in a frame starts a try. A try that got no Transfer for the wait (30 s in a
     * run) ends as a no-transfer: the bot walks back along the line it came in on until it is 3
     * blocks past the edge of the frame, stands there for one tick and walks in again, and its next
     * position in the frame starts the next try. A Transfer ends the walk, the spawn on the new
     * shard starts another.
     * <p>
     * Where the bot is follows from the time since the leg it is on started, like the circles, so a
     * late tick does not slow it down. One thread calls {@link #next}.
     */
    static final class GateWalk {

        /** How far past the edge of the frame a bot walks out after a try that got no Transfer. */
        static final double BACK_OUT = 3;

        /** Where the bot is on one tick, and whether a try started or ran out on it. */
        record Step(double x, double y, double z, boolean entered, boolean noTransfer) {
        }

        private final List<Frame> frames;
        private final long noTransferNanos;
        private final double[] centre;
        /** Where the bot stands after a try that got no Transfer. */
        private final double[] outside;

        private double[] from;
        private double[] to;
        private long legStart;
        private boolean walkingOut;
        private boolean trying;
        private long tryStart;

        /**
         * @param frames          the frames of {@code --gates}, at least one
         * @param spawnedAt       when the server spawned the bot at {@code (x, y, z)}, {@link System#nanoTime()}
         * @param dwellSeconds    how long it stands at the spawn first
         * @param noTransferNanos how long a try waits for a Transfer
         */
        GateWalk(List<Frame> frames, double x, double y, double z, long spawnedAt, double dwellSeconds,
                 long noTransferNanos) {
            if (frames.isEmpty()) throw new IllegalArgumentException("a gate bot needs a frame to walk into");
            this.frames = List.copyOf(frames);
            this.noTransferNanos = noTransferNanos;
            Frame nearest = frames.getFirst();
            for (Frame frame : frames) if (frame.distance(x, y, z) < nearest.distance(x, y, z)) nearest = frame;
            final double centreY = y >= nearest.minY() && y < nearest.maxY() + 1.0 ? y
                    : (nearest.minY() + nearest.maxY() + 1.0) / 2;
            this.centre = new double[]{(nearest.minX() + nearest.maxX() + 1.0) / 2, centreY,
                    (nearest.minZ() + nearest.maxZ() + 1.0) / 2};
            this.from = new double[]{x, y, z};
            this.to = centre;
            this.legStart = spawnedAt + (long) (dwellSeconds * 1e9);
            final double length = distance(from, centre);
            final double[] heading = length == 0 ? new double[]{1, 0, 0}
                    : new double[]{(centre[0] - x) / length, (centre[1] - y) / length, (centre[2] - z) / length};
            this.outside = along(centre, heading, -(backToTheEdge(nearest, heading) + BACK_OUT));
        }

        /** Where the bot is at {@code now}, {@link System#nanoTime()}, not earlier than the last call. */
        Step next(long now) {
            final double[] at;
            if (walkingOut && now - legStart >= (long) (distance(from, to) / GATE.speed * 1e9)) {
                // Out: stand there this tick, walk in again from the next
                at = outside;
                walkingOut = false;
                from = outside;
                to = centre;
                legStart = now;
            } else {
                at = position(now);
            }
            boolean entered = false;
            boolean noTransfer = false;
            if (!walkingOut) {
                if (!trying && inAFrame(at)) {
                    trying = true;
                    tryStart = now;
                    entered = true;
                } else if (trying && now - tryStart >= noTransferNanos) {
                    trying = false;
                    noTransfer = true;
                    walkingOut = true;
                    from = at;
                    to = outside;
                    legStart = now;
                }
            }
            return new Step(at[0], at[1], at[2], entered, noTransfer);
        }

        private double[] position(long now) {
            if (now <= legStart) return from;
            final double length = distance(from, to);
            final double walked = GATE.speed * (now - legStart) / 1e9;
            if (walked >= length) return to;
            return along(from, new double[]{(to[0] - from[0]) / length, (to[1] - from[1]) / length,
                    (to[2] - from[2]) / length}, walked);
        }

        private boolean inAFrame(double[] at) {
            for (Frame frame : frames) if (frame.contains(at[0], at[1], at[2])) return true;
            return false;
        }

        /** How far the centre is from the edge of the frame, going back against {@code heading}. */
        private double backToTheEdge(Frame frame, double[] heading) {
            return Math.min(toTheEdge(centre[0], -heading[0], frame.minX(), frame.maxX()),
                    Math.min(toTheEdge(centre[1], -heading[1], frame.minY(), frame.maxY()),
                            toTheEdge(centre[2], -heading[2], frame.minZ(), frame.maxZ())));
        }

        private static double toTheEdge(double from, double direction, int min, int max) {
            if (direction > 0) return (max + 1.0 - from) / direction;
            if (direction < 0) return (min - from) / direction;
            return Double.MAX_VALUE;
        }

        private static double[] along(double[] from, double[] direction, double distance) {
            return new double[]{from[0] + direction[0] * distance, from[1] + direction[1] * distance,
                    from[2] + direction[2] * distance};
        }

        private static double distance(double[] a, double[] b) {
            final double dx = b[0] - a[0];
            final double dy = b[1] - a[1];
            final double dz = b[2] - a[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}
