package io.github.bearisbug.synaxis.instance;

/**
 * Used to customize which type of {@link Chunk} an implementation should use.
 */
@FunctionalInterface
public interface ChunkSupplier {

    /**
     * Creates a {@link Chunk} object.
     * <p>
     * The chunk returned is a new object that belongs to {@code instance} and sits at ({@code chunkX},
     * {@code chunkZ}). A chunk of another instance or of other coordinates fails the load with an
     * {@link IllegalStateException}.
     *
     * @param instance the linked instance
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return a newly {@link Chunk} object, cannot be null
     */
    Chunk createChunk(Instance instance, int chunkX, int chunkZ);
}
