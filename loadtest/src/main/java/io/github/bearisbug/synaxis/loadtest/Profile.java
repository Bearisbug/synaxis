package io.github.bearisbug.synaxis.loadtest;

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
 * {@link #TOWN} is the exception: the crowd of a town square ({@code docs/DESIGN-PROD.md} §15, the
 * visible entities row), every bot walking a circle of 4 to 32 blocks, so that they all stay within
 * 64 blocks of the spawn and in sight of each other.
 */
enum Profile {
    WALK(4.317, 64, 192, 0),
    RIDE(10.0, 128, 384, 0),
    FLY(30.0, 256, 768, 24),
    TOWN(4.317, 4, 32, 0);

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
            throw new IllegalArgumentException("--profile is walk, ride, fly or town, not " + value, e);
        }
    }

    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Where bot {@code index} is {@code seconds} after it started moving from {@code (x0, y0, z0)}.
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
}
