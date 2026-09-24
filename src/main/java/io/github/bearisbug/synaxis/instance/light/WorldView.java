package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;

/**
 * Read-only block access, the only input of {@link LightEngine}. Coordinates are world block coordinates;
 * {@link #getBlock} is only called for chunks {@link #hasChunk} accepts and for {@code minY <= y < minY + height}.
 */
@ApiStatus.Experimental
public interface WorldView {
    int minY();

    /** World height in blocks, a multiple of 16. */
    int height();

    boolean hasChunk(int chunkX, int chunkZ);

    Block getBlock(int x, int y, int z);
}
