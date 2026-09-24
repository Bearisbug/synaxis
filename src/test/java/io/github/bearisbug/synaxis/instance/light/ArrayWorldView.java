package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;

import java.util.Objects;

/** A {@link WorldView} over plain arrays, chunks {@code -radius..radius} on both axes, with no instance behind it. */
final class ArrayWorldView implements WorldView {
    private final int radius;
    private final int minY;
    private final int height;
    private final char[][] chunks;

    ArrayWorldView(int radius, int minY, int height) {
        this.radius = radius;
        this.minY = minY;
        this.height = height;
        final int side = radius * 2 + 1;
        this.chunks = new char[side * side][];
        for (int i = 0; i < chunks.length; i++) chunks[i] = new char[16 * 16 * height];
    }

    void set(int x, int y, int z, Block block) {
        chunks[chunk(x >> 4, z >> 4)][index(x, y, z)] = (char) block.stateId();
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
        return Math.abs(chunkX) <= radius && Math.abs(chunkZ) <= radius;
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        return Objects.requireNonNull(Block.fromStateId(chunks[chunk(x >> 4, z >> 4)][index(x, y, z)]));
    }

    private int chunk(int chunkX, int chunkZ) {
        if (!hasChunk(chunkX, chunkZ)) throw new IllegalArgumentException("chunk " + chunkX + "," + chunkZ);
        return (chunkX + radius) * (radius * 2 + 1) + chunkZ + radius;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) << 8) | ((z & 15) << 4) | (x & 15);
    }
}
