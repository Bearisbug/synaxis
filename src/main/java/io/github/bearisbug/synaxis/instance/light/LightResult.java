package io.github.bearisbug.synaxis.instance.light;

import org.jetbrains.annotations.ApiStatus;

/**
 * Sky and block light of a rectangle of chunks, as computed by {@link LightEngine}. Each section is a 2,048 byte
 * nibble array indexed {@code x | z << 4 | y << 8}, the layout {@link Light#set} takes. A section that is all 0 is
 * {@link LightCompute#EMPTY_CONTENT} and one that is all 15 is {@link LightCompute#CONTENT_FULLY_LIT}; neither
 * may be written to. Chunks the view did not have are all 0.
 */
@ApiStatus.Experimental
public final class LightResult {
    private final int chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ;
    private final int minSection, sectionCount;
    private final byte[][] sky, block;

    LightResult(int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, int minSection, int sectionCount,
                byte[][] sky, byte[][] block) {
        this.chunkMinX = chunkMinX;
        this.chunkMinZ = chunkMinZ;
        this.chunkMaxX = chunkMaxX;
        this.chunkMaxZ = chunkMaxZ;
        this.minSection = minSection;
        this.sectionCount = sectionCount;
        this.sky = sky;
        this.block = block;
    }

    public byte[] sky(int chunkX, int sectionY, int chunkZ) {
        return sky[index(chunkX, sectionY, chunkZ)];
    }

    public byte[] block(int chunkX, int sectionY, int chunkZ) {
        return block[index(chunkX, sectionY, chunkZ)];
    }

    public int skyLevel(int x, int y, int z) {
        return LightCompute.getLight(sky(x >> 4, y >> 4, z >> 4), x & 15, y & 15, z & 15);
    }

    public int blockLevel(int x, int y, int z) {
        return LightCompute.getLight(block(x >> 4, y >> 4, z >> 4), x & 15, y & 15, z & 15);
    }

    private int index(int chunkX, int sectionY, int chunkZ) {
        if (chunkX < chunkMinX || chunkX > chunkMaxX || chunkZ < chunkMinZ || chunkZ > chunkMaxZ
                || sectionY < minSection || sectionY >= minSection + sectionCount) {
            throw new IndexOutOfBoundsException("section " + chunkX + "," + sectionY + "," + chunkZ + " is outside the result");
        }
        return ((chunkX - chunkMinX) * (chunkMaxZ - chunkMinZ + 1) + (chunkZ - chunkMinZ)) * sectionCount + sectionY - minSection;
    }
}
