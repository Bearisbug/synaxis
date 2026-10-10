package io.github.bearisbug.synaxis.synx;

import java.util.function.UnaryOperator;

/**
 * The overlay store settings of a shard ({@code docs/DESIGN-M3.md} §9, {@code API-M3-007}).
 *
 * @param cap                  {@code overlay_cap}: how many overlay chunks stay resident, a soft cap, at least 1
 * @param flushIntervalSeconds {@code flush_interval_s}: how often a dirty chunk is written, 1 to 60
 * @param maxBytes             {@code overlay_max_bytes}: once the segment files hold this many bytes written,
 *                             the map is read only to players and plugins, at least 1
 */
public record OverlayOptions(int cap, int flushIntervalSeconds, long maxBytes) {
    public static final String CAP = "synaxis.overlay.cap";
    public static final String FLUSH_INTERVAL = "synaxis.overlay.flush-interval-s";
    public static final String MAX_BYTES = "synaxis.overlay.max-bytes";
    public static final int DEFAULT_CAP = 16_384;
    public static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 10;
    /** 4 GiB: twice the live records of a 4k region changed everywhere, less than its saturation (API-M3-007). */
    public static final long DEFAULT_MAX_BYTES = 4L << 30;

    public OverlayOptions {
        if (cap < 1) throw new IllegalArgumentException("overlay_cap must be at least 1: " + cap);
        if (flushIntervalSeconds < 1 || flushIntervalSeconds > 60) {
            throw new IllegalArgumentException("flush_interval_s must be from 1 to 60: " + flushIntervalSeconds);
        }
        if (maxBytes < 1) throw new IllegalArgumentException("overlay_max_bytes must be at least 1: " + maxBytes);
    }

    /** The cap and the flush interval, on the default {@value #DEFAULT_MAX_BYTES} bytes written. */
    public OverlayOptions(int cap, int flushIntervalSeconds) {
        this(cap, flushIntervalSeconds, DEFAULT_MAX_BYTES);
    }

    /**
     * Reads {@value #CAP} (default 16,384), {@value #FLUSH_INTERVAL} (default 10) and {@value #MAX_BYTES}
     * (default 4 GiB) from the system properties, which a map shard does in step (1) of {@code Main}
     * ({@code docs/DESIGN-M3.md} {@code API-M3-006}).
     *
     * @throws IllegalArgumentException naming the key, for a value that is not what it takes
     */
    public static OverlayOptions fromSystemProperties() {
        return from(System::getProperty);
    }

    static OverlayOptions from(UnaryOperator<String> properties) {
        return new OverlayOptions(intProperty(properties, CAP, DEFAULT_CAP, 1, Integer.MAX_VALUE),
                intProperty(properties, FLUSH_INTERVAL, DEFAULT_FLUSH_INTERVAL_SECONDS, 1, 60),
                bytesProperty(properties, MAX_BYTES, DEFAULT_MAX_BYTES));
    }

    private static int intProperty(UnaryOperator<String> properties, String name, int defaultValue, int min, int max) {
        final String value = properties.apply(name);
        if (value == null) return defaultValue;
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not an integer: " + value, e);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("Property '" + name + "' is not from " + min
                    + (max == Integer.MAX_VALUE ? " up" : " to " + max) + ": " + value);
        }
        return parsed;
    }

    /** A number of bytes, at least 1, written as {@code synaxis.synx.section-cache-bytes} is. */
    private static long bytesProperty(UnaryOperator<String> properties, String name, long defaultValue) {
        final String value = properties.apply(name);
        if (value == null) return defaultValue;
        final long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not a number of bytes: " + value, e);
        }
        if (parsed < 1) throw new IllegalArgumentException("Property '" + name + "' is below 1: " + value);
        return parsed;
    }
}
