package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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
     * The state ids of the section of chunk {@code (chunkX, chunkZ)} whose lowest block y is {@code sectionMinY},
     * each the one {@link #getBlock} gives, indexed {@code x | z << 4 | y << 8} by section-local coordinates. When
     * every block of it is {@link Block#AIR} it may be null, which the engine reads the same as an array of zeros.
     * The engine only reads the array. The default asks {@link #getBlock} block by block; a view that holds the
     * section's palette can copy it out at once.
     */
    default char @Nullable [] section(int chunkX, int sectionMinY, int chunkZ) {
        final int air = Block.AIR.stateId();
        final int blockX = chunkX << 4, blockZ = chunkZ << 4;
        char[] states = null;
        for (int i = 0; i < 4096; i++) {
            final int stateId = getBlock(blockX | (i & 15), sectionMinY + (i >> 8), blockZ | ((i >> 4) & 15)).stateId();
            if (stateId == air) continue;
            if (states == null) states = new char[4096];
            states[i] = (char) stateId;
        }
        return states;
    }
}
