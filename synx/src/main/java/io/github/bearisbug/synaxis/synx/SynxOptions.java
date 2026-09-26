package io.github.bearisbug.synaxis.synx;

/**
 * The {@code :synx} settings of a shard ({@code docs/DESIGN-M3.md} §9).
 *
 * @param worldWritable     {@code world_writable}: false makes every chunk read only
 * @param sectionCacheBytes {@code section_cache_bytes}: the budget of the decoded section cache, at least 1
 */
public record SynxOptions(boolean worldWritable, long sectionCacheBytes) {
    public SynxOptions {
        if (sectionCacheBytes < 1) {
            throw new IllegalArgumentException("section_cache_bytes must be at least 1: " + sectionCacheBytes);
        }
    }
}
