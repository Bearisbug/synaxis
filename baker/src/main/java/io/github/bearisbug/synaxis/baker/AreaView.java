package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The chunks one light computation reads: a rectangle of source chunks, null where the rectangle leaves the
 * map. The light engine treats those as missing, they neither emit nor pass light.
 */
final class AreaView implements WorldView {
    private final int minY;
    private final int height;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    private final @Nullable SourceChunk[] chunks;

    /** {@code chunks[(x - originX) * depth + (z - originZ)]}. */
    AreaView(int minY, int height, int originX, int originZ, int width, int depth, @Nullable SourceChunk[] chunks) {
        if (chunks.length != width * depth) throw new IllegalArgumentException("one entry per chunk of the area");
        this.minY = minY;
        this.height = height;
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.chunks = chunks;
    }

    @Nullable SourceChunk chunk(int chunkX, int chunkZ) {
        final int x = chunkX - originX, z = chunkZ - originZ;
        if (x < 0 || x >= width || z < 0 || z >= depth) return null;
        return chunks[x * depth + z];
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return chunk(chunkX, chunkZ) != null;
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        final SourceChunk chunk = Objects.requireNonNull(chunk(x >> 4, z >> 4));
        final int state = chunk.blocks()[(y - minY) >> 4].get(x & 15, y & 15, z & 15);
        return Objects.requireNonNull(Block.fromStateId(state));
    }
}
