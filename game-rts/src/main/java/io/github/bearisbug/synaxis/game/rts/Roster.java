package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a match starts with. M2 has no production and no combat, so the roster a match is deployed
 * with is the whole of it: the joining player's team around the spawn point, one neutral unit and
 * one of the opposing team beside them, and one more of the player's own far enough away to be out
 * of sight until the camera goes looking for it.
 * <p>
 * How many there are is the operator's to say ({@value #UNITS_PROPERTY}, {@value #DEFAULT_UNITS}
 * when unset). The default is what the acceptance cases need ({@code docs/TEST.md} §3, the unit
 * seed): enough own units to fill a pathfinding queue, a neutral and an enemy one so that
 * {@code owner(unit, sender)} has something to refuse, and a distant one so that a unit really does
 * enter and leave an interest set. The load harness of {@code docs/DESIGN-M2.md} §15 asks for the
 * thousand that table budgets for. Placement is a pure function of the map and the number, so the
 * same preset and the same number always hand out the same {@code unit_id} to the same unit.
 */
final class Roster {

    /** The system property naming how many units a match is deployed with. */
    static final String UNITS_PROPERTY = "synaxis.app.units";

    /** The roster the acceptance cases are written against: 64 own units plus the three fixed ones. */
    static final int DEFAULT_UNITS = 67;

    /** The neutral unit, the enemy unit and the outpost, fielded whatever the number. */
    static final int FIXED_UNITS = 3;

    /** The team a connection is admitted with, and therefore the team of its units. */
    static final int PLAYER_TEAM = 1;

    /** A second team, fielded so that {@code not-owner} has a unit to be about. */
    static final int ENEMY_TEAM = 2;

    /** How far from the spawn the outpost unit is put, when the map is big enough for it. */
    private static final int OUTPOST_DISTANCE = 220;

    private Roster() {
    }

    /**
     * Reads how many units to deploy off the system property.
     *
     * @return the number of units, {@value #DEFAULT_UNITS} when the property is not set
     * @throws IllegalArgumentException if the property is not a number, or names too few units for
     *                                  the three fixed ones and at least one of the player's own
     */
    static int units() {
        return units(System.getProperty(UNITS_PROPERTY));
    }

    /**
     * Parses the value of {@value #UNITS_PROPERTY}.
     *
     * @param property the value, null when the property is not set
     * @return the number of units to deploy
     * @throws IllegalArgumentException if the value is refused, naming the value and why
     */
    static int units(@Nullable String property) {
        if (property == null) return DEFAULT_UNITS;
        final int total;
        try {
            total = Integer.parseInt(property.strip());
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException(UNITS_PROPERTY + "=" + property + " is not a number");
        }
        if (total <= FIXED_UNITS) {
            throw new IllegalArgumentException(UNITS_PROPERTY + "=" + property + " is too few: the roster is "
                    + FIXED_UNITS + " fixed units plus at least one of the player's own");
        }
        return total;
    }

    /**
     * Deploys the roster of a match: {@code total - 3} units of the player's team ring by ring
     * around the spawn, then the neutral unit, the enemy unit and the outpost, numbered in that
     * order.
     * <p>
     * Every column is chosen before the first unit is spawned. A map that cannot hold the roster
     * therefore refuses the whole of it, with nothing on the board, rather than leaving the part
     * that fitted there for the startup sequence to serve.
     *
     * @param units  the units of the match
     * @param grid   the navigation mesh, which decides where a unit may stand
     * @param preset the map, which bounds how far a search for a free column may wander
     * @param spawn  the spawn point of the match, the roster is laid out around it
     * @param total  how many units to deploy, more than {@value #FIXED_UNITS}
     * @throws IllegalStateException if the map has fewer walkable columns than the roster needs
     */
    static void deploy(Units units, NavGrid grid, MapPreset preset, Pos spawn, int total) {
        final int spawnX = (int) Math.floor(spawn.x());
        final int spawnZ = (int) Math.floor(spawn.z());
        final int maxRing = preset.sizeX() + preset.sizeZ();
        final int own = total - FIXED_UNITS;
        final List<Pos> stands = new ArrayList<>(own);
        for (int ring = 1; stands.size() < own; ring++) {
            if (ring > maxRing) {
                throw new IllegalStateException(UNITS_PROPERTY + "=" + total + " does not fit: map_id="
                        + preset.id() + " has " + stands.size() + " walkable columns around the spawn for the "
                        + own + " units of the player's team");
            }
            for (int dx = -ring; dx <= ring && stands.size() < own; dx++) {
                for (int dz = -ring; dz <= ring && stands.size() < own; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (!grid.isWalkable(spawnX + dx, spawnZ + dz)) continue;
                    stands.add(stand(grid, spawnX + dx, spawnZ + dz));
                }
            }
        }
        final Pos neutral = nearestStand(grid, maxRing, spawnX + 3, spawnZ + 3);
        final Pos enemy = nearestStand(grid, maxRing, spawnX - 3, spawnZ - 3);
        final Pos outpost = nearestStand(grid, maxRing, spawnX + OUTPOST_DISTANCE, spawnZ);
        for (final Pos position : stands) units.spawn(Archetype.SOLDIER, PLAYER_TEAM, position);
        units.spawn(Archetype.SOLDIER, 0, neutral);
        units.spawn(Archetype.SOLDIER, ENEMY_TEAM, enemy);
        units.spawn(Archetype.SOLDIER, PLAYER_TEAM, outpost);
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
