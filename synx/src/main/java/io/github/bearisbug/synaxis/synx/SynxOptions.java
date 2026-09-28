package io.github.bearisbug.synaxis.synx;

import java.util.function.UnaryOperator;

/**
 * The {@code :synx} settings of a shard ({@code docs/DESIGN-M3.md} §9).
 *
 * @param worldWritable     {@code world_writable}: false makes every chunk read only
 * @param sectionCacheBytes {@code section_cache_bytes}: the budget of the decoded section cache, at least 1
 * @param relightThreads    {@code relight_threads}: the threads of the light recomputation pool, at least 1
 * @param resendLight       {@code resend_light}: the fallback of {@code ADR-M3-002}, which sends the sections
 *                          whose light an install changed to every player the chunk was delivered to
 */
public record SynxOptions(boolean worldWritable, long sectionCacheBytes, int relightThreads, boolean resendLight) {
    public static final String WORLD_WRITABLE = "synaxis.world-writable";
    public static final String SECTION_CACHE_BYTES = "synaxis.synx.section-cache-bytes";
    public static final String RELIGHT_THREADS = "synaxis.relight-threads";
    public static final String RESEND_LIGHT = "synaxis.relight.resend-light";
    public static final long DEFAULT_SECTION_CACHE_BYTES = 256L * 1024 * 1024;

    public SynxOptions {
        if (sectionCacheBytes < 1) {
            throw new IllegalArgumentException("section_cache_bytes must be at least 1: " + sectionCacheBytes);
        }
        if (relightThreads < 1) {
            throw new IllegalArgumentException("relight_threads must be at least 1: " + relightThreads);
        }
    }

    /** {@code max(1, cores / 4)}, as many as the overlay encode pool has. */
    static int defaultRelightThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }

    /**
     * Reads {@value #WORLD_WRITABLE} (default {@code true}), {@value #SECTION_CACHE_BYTES} (default
     * 256 MiB), {@value #RELIGHT_THREADS} (default {@link #defaultRelightThreads()}) and
     * {@value #RESEND_LIGHT} (default {@code false}) from the system properties, which a map shard
     * does in step (1) of {@code Main} ({@code docs/DESIGN-M3.md} {@code API-M3-006}). A boolean is
     * lower case {@code true} or {@code false} and nothing else, as the boolean keys of
     * {@code ServerConfig}.
     *
     * @throws IllegalArgumentException naming the key, for a value that is not what it takes
     */
    public static SynxOptions fromSystemProperties() {
        return from(System::getProperty);
    }

    static SynxOptions from(UnaryOperator<String> properties) {
        final boolean worldWritable = booleanProperty(properties, WORLD_WRITABLE, true);
        final String cache = properties.apply(SECTION_CACHE_BYTES);
        long sectionCacheBytes = DEFAULT_SECTION_CACHE_BYTES;
        if (cache != null) {
            try {
                sectionCacheBytes = Long.parseLong(cache);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Property '" + SECTION_CACHE_BYTES + "' is not a number of bytes: " + cache, e);
            }
            if (sectionCacheBytes < 1)
                throw new IllegalArgumentException("Property '" + SECTION_CACHE_BYTES + "' is below 1: " + cache);
        }
        final String threads = properties.apply(RELIGHT_THREADS);
        int relightThreads = defaultRelightThreads();
        if (threads != null) {
            try {
                relightThreads = Integer.parseInt(threads);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Property '" + RELIGHT_THREADS + "' is not an integer: " + threads, e);
            }
            if (relightThreads < 1)
                throw new IllegalArgumentException("Property '" + RELIGHT_THREADS + "' is below 1: " + threads);
        }
        return new SynxOptions(worldWritable, sectionCacheBytes, relightThreads,
                booleanProperty(properties, RESEND_LIGHT, false));
    }

    private static boolean booleanProperty(UnaryOperator<String> properties, String name, boolean defaultValue) {
        final String value = properties.apply(name);
        return switch (value) {
            case null -> defaultValue;
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Property '" + name + "' is not true or false: \"" + value + "\"");
        };
    }
}
