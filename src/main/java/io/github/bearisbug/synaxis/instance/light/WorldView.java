package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;

/**
 * Read-only block access, the only input of {@link LightEngine}. Coordinates are world block coordinates;
 * {@link #getBlock} and {@link #section} are only called for chunks {@link #hasChunk} accepts and for
 * {@code minY <= y < minY + height}.
 */
@ApiStatus.Experimental
public interface WorldView {
    /** Lowest block y of the world, a multiple of 16. */
    int minY();

    /** World height in blocks, a multiple of 16. */
    int height();

    boolean hasChunk(int chunkX, int chunkZ);

    Block getBlock(int x, int y, int z);

    /**
     * Writes the state ids of the section of chunk {@code (chunkX, chunkZ)} whose lowest block y is
     * {@code sectionMinY} into all 4,096 entries of {@code states}, each the one {@link #getBlock} gives, indexed
     * {@code x | z << 4 | y << 8} by section-local coordinates. Returns false when every block of it is
     * {@link Block#AIR}, and may then leave {@code states} as it found it; it may also write such a section out as
     * zeros and return true, which the engine reads the same. The array is the engine's, which hands it out again:
     * do not keep it. The default asks {@link #getBlock} block by block; a view that holds the section's palette can
     * copy it out at once.
     */
    default boolean section(int chunkX, int sectionMinY, int chunkZ, char[] states) {
        final int air = Block.AIR.stateId();
        final int blockX = chunkX << 4, blockZ = chunkZ << 4;
        boolean blocks = false;
        for (int i = 0; i < 4096; i++) {
            final int stateId = getBlock(blockX | (i & 15), sectionMinY + (i >> 8), blockZ | ((i >> 4) & 15)).stateId();
            states[i] = (char) stateId;
            if (stateId != air) blocks = true;
        }
        return blocks;
    }
}
