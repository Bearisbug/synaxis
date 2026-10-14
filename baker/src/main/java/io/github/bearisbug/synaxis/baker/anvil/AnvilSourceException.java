package io.github.bearisbug.synaxis.baker.anvil;

import java.io.IOException;

/**
 * The source save is refused at one chunk, any source error of API-M3-003 found in what the chunk holds or
 * lacks, such as a wrong DataVersion or status, a block or biome that is not 26.2's, a data marker the baker
 * cannot map or content outside of the baked window. The baker reports it with exit code 2; a region file or
 * entry that does not read is a plain {@link IOException} naming the chunk and the file, with the same exit code.
 */
public final class AnvilSourceException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int chunkX;
    private final int chunkZ;

    public AnvilSourceException(int chunkX, int chunkZ, String message) {
        super("chunk " + chunkX + ", " + chunkZ + ": " + message);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }
}
