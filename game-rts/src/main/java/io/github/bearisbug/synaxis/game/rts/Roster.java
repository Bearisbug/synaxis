package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;

/**
 * What a match starts with. M2 has no production and no combat, so the roster a match is deployed
 * with is the whole of it: {@value #OWN_UNITS} units of the joining player's team around the spawn
 * point, one neutral unit and one of the opposing team beside them, and one more of the player's
 * own far enough away to be out of sight until the camera goes looking for it.
 * <p>
 * The numbers are the ones the acceptance cases need ({@code docs/TEST.md} §3, the unit seed):
 * enough own units to fill a pathfinding queue, a neutral and an enemy one so that
 * {@code owner(unit, sender)} has something to refuse, and a distant one so that a unit really does
 * enter and leave an interest set. Placement is a pure function of the map, so the same preset
 * always hands out the same {@code unit_id} to the same unit.
 */
final class Roster {

    /** How many units the joining player commands. */
    static final int OWN_UNITS = 64;

    /** The team a connection is admitted with, and therefore the team of its units. */
    static final int PLAYER_TEAM = 1;

    /** A second team, fielded so that {@code not-owner} has a unit to be about. */
    static final int ENEMY_TEAM = 2;

    /** How far from the spawn the outpost unit is put, when the map is big enough for it. */
    private static final int OUTPOST_DISTANCE = 220;

    private Roster() {
    }

    /**
     * Deploys the roster of a match.
     *
     * @param units  the units of the match
     * @param grid   the navigation mesh, which decides where a unit may stand
     * @param preset the map, which bounds how far a search for a free column may wander
     * @param spawn  the spawn point of the match, the roster is laid out around it
     */
    static void deploy(Units units, NavGrid grid, MapPreset preset, Pos spawn) {
        final int spawnX = (int) Math.floor(spawn.x());
        final int spawnZ = (int) Math.floor(spawn.z());
        final int maxRing = preset.sizeX() + preset.sizeZ();
        int placed = 0;
        for (int ring = 1; placed < OWN_UNITS; ring++) {
            if (ring > maxRing) throw new IllegalStateException("the map has nowhere to put " + OWN_UNITS + " units");
            for (int dx = -ring; dx <= ring && placed < OWN_UNITS; dx++) {
                for (int dz = -ring; dz <= ring && placed < OWN_UNITS; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (!grid.isWalkable(spawnX + dx, spawnZ + dz)) continue;
                    units.spawn(Archetype.SOLDIER, PLAYER_TEAM, stand(grid, spawnX + dx, spawnZ + dz));
                    placed++;
                }
            }
        }
        units.spawn(Archetype.SOLDIER, 0, nearestStand(grid, maxRing, spawnX + 3, spawnZ + 3));
        units.spawn(Archetype.SOLDIER, ENEMY_TEAM, nearestStand(grid, maxRing, spawnX - 3, spawnZ - 3));
        units.spawn(Archetype.SOLDIER, PLAYER_TEAM,
                nearestStand(grid, maxRing, spawnX + OUTPOST_DISTANCE, spawnZ));
    }

    /** The walkable column closest to the one asked for, searched outwards from it. */
    private static Pos nearestStand(NavGrid grid, int maxRing, int x, int z) {
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (grid.isWalkable(x + dx, z + dz)) return stand(grid, x + dx, z + dz);
                }
            }
        }
        throw new IllegalStateException("no walkable column anywhere near " + x + "," + z);
    }

    /** The middle of a column, at the height a unit stands on it. */
    private static Pos stand(NavGrid grid, int x, int z) {
        return new Pos(x + 0.5, grid.surfaceY(x, z), z + 0.5);
    }
}
