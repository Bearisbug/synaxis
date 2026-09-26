package io.github.bearisbug.synaxis.synx;

/**
 * The overlay store settings of a shard ({@code docs/DESIGN-M3.md} §9). The shard host reads them
 * in step (1) of {@code Main}, before anything touches the disk (API-M3-006).
 *
 * @param cap                  {@code overlay_cap}: the soft limit of chunks in overlay mode, at least 1
 * @param flushIntervalSeconds {@code flush_interval_s}: how long a chunk stays dirty before the write phase writes
 *                             it, 1 to 60
 */
public record OverlayOptions(int cap, int flushIntervalSeconds) {
    public static final String CAP_PROPERTY = "synaxis.overlay.cap";
    public static final String FLUSH_INTERVAL_PROPERTY = "synaxis.overlay.flush-interval-s";

    /** The test ring's cap; the production ring sets 24,576. */
    public static final int DEFAULT_CAP = 16_384;
    public static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 10;
    private static final int MAX_FLUSH_INTERVAL_SECONDS = 60;

    public OverlayOptions {
        if (cap < 1) throw new IllegalArgumentException("overlay_cap must be at least 1: " + cap);
        if (flushIntervalSeconds < 1 || flushIntervalSeconds > MAX_FLUSH_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("flush_interval_s must be between 1 and 60: " + flushIntervalSeconds);
        }
    }

    /**
     * Reads {@value #CAP_PROPERTY} and {@value #FLUSH_INTERVAL_PROPERTY}. A value is taken as it
     * is, blanks included, the rule of the {@code ServerConfig} keys.
     *
     * @throws IllegalArgumentException naming the key, if a value is not an integer or out of range; the
     *                                  shard host refuses to start with it
     */
    public static OverlayOptions fromSystemProperties() {
        final int cap = intProperty(CAP_PROPERTY, DEFAULT_CAP);
        if (cap < 1) throw new IllegalArgumentException("Property '" + CAP_PROPERTY + "' must be at least 1: " + cap);
        final int interval = intProperty(FLUSH_INTERVAL_PROPERTY, DEFAULT_FLUSH_INTERVAL_SECONDS);
        if (interval < 1 || interval > MAX_FLUSH_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("Property '" + FLUSH_INTERVAL_PROPERTY + "' must be between 1 and "
                    + MAX_FLUSH_INTERVAL_SECONDS + ": " + interval);
        }
        return new OverlayOptions(cap, interval);
    }

    private static int intProperty(String name, int defaultValue) {
        final String value = System.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not an integer: \"" + value + "\"", e);
        }
    }
}
