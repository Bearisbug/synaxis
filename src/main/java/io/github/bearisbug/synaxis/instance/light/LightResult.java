package io.github.bearisbug.synaxis.instance.light;

import org.jetbrains.annotations.ApiStatus;

/**
 * Sky and block light of a rectangle of chunks, as computed by {@link LightEngine}. Each section is a 2,048 byte
 * nibble array indexed {@code x | z << 4 | y << 8}, the layout {@link Light#set} takes. A section no light reaches,
 * and every section of a chunk the view did not have, is {@link LightCompute#EMPTY_CONTENT}; a sky section that is
 * all sky source is {@link LightCompute#CONTENT_FULLY_LIT}. Neither may be written to. Every other section is a new
 * array, even one that is all 15 (block light of a section full of lava), so compare contents, not identity.
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

    /** Sky light of a section; {@code sectionY} is its own y ({@code blockY >> 4}), not an index from the bottom. */
    public byte[] sky(int chunkX, int sectionY, int chunkZ) {
        return sky[index(chunkX, sectionY, chunkZ)];
    }

    /** Block light of a section; {@code sectionY} is its own y ({@code blockY >> 4}), not an index from the bottom. */
    public byte[] block(int chunkX, int sectionY, int chunkZ) {
        return block[index(chunkX, sectionY, chunkZ)];
    }

    /** Sky light level at world block coordinates. */
    public int skyLevel(int x, int y, int z) {
        return LightCompute.getLight(sky(x >> 4, y >> 4, z >> 4), x & 15, y & 15, z & 15);
    }

    /** Block light level at world block coordinates. */
    public int blockLevel(int x, int y, int z) {
        return LightCompute.getLight(block(x >> 4, y >> 4, z >> 4), x & 15, y & 15, z & 15);
    }

    private int index(int chunkX, int sectionY, int chunkZ) {
        if (chunkX < chunkMinX || chunkX > chunkMaxX || chunkZ < chunkMinZ || chunkZ > chunkMaxZ
                || sectionY < minSection || sectionY >= minSection + sectionCount) {
            throw new IndexOutOfBoundsException("section " + chunkX + "," + sectionY + "," + chunkZ
                    + " is outside the result, chunks " + chunkMinX + "," + chunkMinZ + ".." + chunkMaxX + "," + chunkMaxZ
                    + ", section y " + minSection + ".." + (minSection + sectionCount - 1));
        }
        return ((chunkX - chunkMinX) * (chunkMaxZ - chunkMinZ + 1) + (chunkZ - chunkMinZ)) * sectionCount + sectionY - minSection;
    }
}
