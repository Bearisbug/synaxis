package io.github.bearisbug.synaxis.synx;

import java.util.function.UnaryOperator;

/**
 * The {@code :synx} settings of a shard ({@code docs/DESIGN-M3.md} §9).
 *
 * @param worldWritable     {@code world_writable}: false makes every chunk read only
 * @param sectionCacheBytes {@code section_cache_bytes}: the budget of the decoded section cache, at least 1
 */
public record SynxOptions(boolean worldWritable, long sectionCacheBytes) {
    public static final String WORLD_WRITABLE = "synaxis.world-writable";
    public static final String SECTION_CACHE_BYTES = "synaxis.synx.section-cache-bytes";
    public static final long DEFAULT_SECTION_CACHE_BYTES = 256L * 1024 * 1024;

    public SynxOptions {
        if (sectionCacheBytes < 1) {
            throw new IllegalArgumentException("section_cache_bytes must be at least 1: " + sectionCacheBytes);
        }
    }

    /**
     * Reads {@value #WORLD_WRITABLE} (default {@code true}) and {@value #SECTION_CACHE_BYTES} (default
     * 256 MiB) from the system properties, which a map shard does in step (1) of {@code Main}
     * ({@code docs/DESIGN-M3.md} {@code API-M3-006}). A boolean is lower case {@code true} or
     * {@code false} and nothing else, as the boolean keys of {@code ServerConfig}.
     *
     * @throws IllegalArgumentException naming the key, for a value that is not what it takes
     */
    public static SynxOptions fromSystemProperties() {
        return from(System::getProperty);
    }

    static SynxOptions from(UnaryOperator<String> properties) {
        final String writable = properties.apply(WORLD_WRITABLE);
        final boolean worldWritable = switch (writable) {
            case null -> true;
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Property '" + WORLD_WRITABLE + "' is not true or false: \""
                    + writable + "\"");
        };
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
        return new SynxOptions(worldWritable, sectionCacheBytes);
    }
}
