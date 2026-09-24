package io.github.bearisbug.synaxis.baker.anvil;

import java.io.IOException;

/**
 * The source save is not acceptable for baking at one chunk: wrong DataVersion, not generated to
 * full, or a data marker the baker cannot map. The baker reports it with exit code 2 (API-M3-003).
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
