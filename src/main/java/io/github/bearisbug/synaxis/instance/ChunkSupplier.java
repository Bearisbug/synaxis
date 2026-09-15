package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Used to customize which type of {@link Chunk} an implementation should use.
 */
@FunctionalInterface
public interface ChunkSupplier {

    /**
     * Creates a {@link Chunk} object.
     *
     * @param instance the linked instance
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return a newly {@link Chunk} object, cannot be null
     */
    Chunk createChunk(Instance instance, int chunkX, int chunkZ);
}
