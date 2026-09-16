package io.github.bearisbug.synaxis.game.rts;

/**
 * The shape of a generated map, as a pure function of the column (REQ-M2-010).
 * <p>
 * It is the single source both consumers read: {@link MapGenerator} turns it into blocks and
 * {@link NavGrid} turns it into the walkable set, so the world and the navigation mesh cannot
 * disagree. Being a function of the column also means chunks can be generated in any order on any
 * thread, which is what the engine does.
 * <p>
 * The map is flat ground at {@link #GROUND_HEIGHT} with two things on it: scattered pillars, and
 * one walled off pocket that nothing can walk into. The pocket is what gives §26 stage 5 a
 * pathfinding target that has to come back {@code no-path}.
 */
final class MapTerrain {

    /** The number of full ground layers, so the standing surface is y = GROUND_HEIGHT. */
    static final int GROUND_HEIGHT = 40;

    /** Pillars and pocket walls are this high: more than the one block a unit may step up. */
    static final int OBSTACLE_HEIGHT = 3;

    /** One column in this many carries a pillar, which leaves the rest of the map connected. */
    private static final int PILLAR_RARITY = 64;

    private final MapPreset preset;
    private final int pocketMinX;
    private final int pocketMinZ;
    private final int pocketSize;

    MapTerrain(MapPreset preset) {
        this.preset = preset;
        this.pocketSize = 16;
        this.pocketMinX = preset.sizeX() / 8;
        this.pocketMinZ = preset.sizeZ() / 8;
    }

    MapPreset preset() {
        return preset;
    }

    /**
     * The height of the obstacle standing on a column.
     *
     * @param x a block x
     * @param z a block z
     * @return the obstacle height, 0 when the column carries none
     */
    int obstacleHeight(int x, int z) {
        if (isPocketWall(x, z)) return OBSTACLE_HEIGHT;
        if (isInsidePocket(x, z)) return 0; // Walkable, but walled in
        return hash(x, z) % PILLAR_RARITY == 0 ? OBSTACLE_HEIGHT : 0;
    }

    /**
     * Whether a unit can stand on a column.
     *
     * @param x a block x
     * @param z a block z
     * @return true when the column is inside the map and carries no obstacle
     */
    boolean isWalkable(int x, int z) {
        return preset.contains(x, z) && obstacleHeight(x, z) == 0;
    }

    /**
     * Whether a column belongs to the walled off pocket.
     *
     * @param x a block x
     * @param z a block z
     * @return true for the pocket and its wall alike
     */
    boolean isInPocketArea(int x, int z) {
        return x >= pocketMinX && x < pocketMinX + pocketSize
                && z >= pocketMinZ && z < pocketMinZ + pocketSize;
    }

    private boolean isInsidePocket(int x, int z) {
        return x > pocketMinX && x < pocketMinX + pocketSize - 1
                && z > pocketMinZ && z < pocketMinZ + pocketSize - 1;
    }

    private boolean isPocketWall(int x, int z) {
        return isInPocketArea(x, z) && !isInsidePocket(x, z);
    }

    /**
     * A cheap deterministic scatter. It only has to be stable for a seed and spread out enough that
     * the pillars do not line up into walls, not to be statistically sound.
     */
    private int hash(int x, int z) {
        long value = preset.seed() ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 29;
        return (int) (value & 0x7FFFFFFF);
    }
}
