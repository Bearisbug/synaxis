package io.github.bearisbug.synaxis.game.rts;

/**
 * The navigation mesh of a map: one walkable bit and one standing height per column (§7, §9).
 * <p>
 * It comes out of the same {@link MapTerrain} as the blocks and in the same pass as the map is
 * built (REQ-M2-010), and is immutable from then on: the pathfinding of REQ-M2-017 reads it from
 * the instance tick thread without any locking.
 */
public final class NavGrid {

    private final int sizeX;
    private final int sizeZ;
    private final long[] walkable;
    private final short[] surfaceY;

    private NavGrid(int sizeX, int sizeZ, long[] walkable, short[] surfaceY) {
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.walkable = walkable;
        this.surfaceY = surfaceY;
    }

    static NavGrid of(MapTerrain terrain) {
        final MapPreset preset = terrain.preset();
        final int columns = preset.sizeX() * preset.sizeZ();
        final long[] walkable = new long[(columns + 63) / 64];
        final short[] surfaceY = new short[columns];
        for (int z = 0; z < preset.sizeZ(); z++) {
            for (int x = 0; x < preset.sizeX(); x++) {
                final int index = z * preset.sizeX() + x;
                surfaceY[index] = (short) (MapTerrain.GROUND_HEIGHT + terrain.obstacleHeight(x, z));
                if (terrain.isWalkable(x, z)) walkable[index >> 6] |= 1L << (index & 63);
            }
        }
        return new NavGrid(preset.sizeX(), preset.sizeZ(), walkable, surfaceY);
    }

    /**
     * Whether a unit can stand on a column.
     *
     * @param x a block x
     * @param z a block z
     * @return true when the column is walkable; false outside the map
     */
    public boolean isWalkable(int x, int z) {
        if (!contains(x, z)) return false;
        final int index = z * sizeX + x;
        return (walkable[index >> 6] & (1L << (index & 63))) != 0;
    }

    /**
     * The height a unit stands at on a column.
     *
     * @param x a block x
     * @param z a block z
     * @return the standing y of that column
     * @throws IndexOutOfBoundsException if the column is outside the map
     */
    public int surfaceY(int x, int z) {
        if (!contains(x, z)) throw new IndexOutOfBoundsException("column " + x + "," + z + " is outside the map");
        return surfaceY[z * sizeX + x];
    }

    private boolean contains(int x, int z) {
        return x >= 0 && x < sizeX && z >= 0 && z < sizeZ;
    }
}
