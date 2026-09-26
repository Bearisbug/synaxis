package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.LightingChunk;

/**
 * Stands in for a class of the shard package that lights the map through {@code LightingChunk}.
 * Only ever imported as a synthetic {@code JavaClasses}; the scan of the package leaves it out through
 * {@code DoNotIncludeTests}.
 */
final class ShardClassLightingThroughLightingChunk {

    private ShardClassLightingThroughLightingChunk() {
    }

    @SuppressWarnings("unused")
    static LightingChunk light(Instance instance) {
        return new LightingChunk(instance, 0, 0);
    }
}
