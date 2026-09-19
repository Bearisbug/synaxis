package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search of REQ-M2-017 on the generated map: what it finds, what it refuses to find, and what
 * the string pull is allowed to do to the result.
 */
class PathfinderTest {

    private static final MapPreset PRESET = MapPreset.SMALL;
    private static final float RADIUS = Archetype.SOLDIER.radius();

    private final MapTerrain terrain = new MapTerrain(PRESET);
    private final NavGrid grid = NavGrid.of(terrain);
    private final Pathfinder pathfinder = new Pathfinder(grid, PRESET);

    @Test
    void aTargetOnTheColumnTheUnitStandsOnIsOneWaypoint() {
        final int[] open = openRun(1);

        final Pathfinder.Path path = pathfinder.find(stand(open[0], open[1]), open[0], open[1], RADIUS);

        assertTrue(path.found());
        assertEquals(List.of(stand(open[0], open[1])), path.waypoints());
        assertEquals(0, path.nodes(), "a search that does not have to leave its column expands nothing");
    }

    @Test
    void aStraightRunOverOpenGroundIsPulledIntoASingleWaypoint() {
        final int[] open = openRun(12);
        final int x = open[0];
        final int z = open[1];

        final Pathfinder.Path path = pathfinder.find(stand(x, z), x + 11, z, RADIUS);

        assertTrue(path.found());
        assertEquals(List.of(stand(x + 11, z)), path.waypoints(),
                "nothing is in the way, so the staircase A* produced had to collapse into one leg");
    }

    @Test
    void aPathAroundAnObstacleKeepsEveryLegWalkable() {
        final int[] blocked = firstObstacle();
        final int x = blocked[0] - 6;
        final int z = blocked[1];
        final Pos from = stand(x, z);

        final Pathfinder.Path path = pathfinder.find(from, blocked[0] + 6, z, RADIUS);

        assertTrue(path.found(), "the pillar is a detour, not a wall");
        Pos previous = from;
        for (final Pos waypoint : path.waypoints()) {
            assertTrue(grid.isWalkable((int) waypoint.x(), (int) waypoint.z()),
                    "a waypoint on a column nothing can stand on: " + waypoint);
            assertTrue(walkableBetween(previous, waypoint),
                    "the string pull cut a leg through something: " + previous + " → " + waypoint);
            previous = waypoint;
        }
        assertEquals(stand(blocked[0] + 6, z), path.waypoints().getLast());
    }

    @Test
    void aTargetWalledInHasNoPathEvenThoughItIsWalkable() {
        final int[] pocket = insideThePocket();
        final int[] open = openRun(1);
        assertTrue(grid.isWalkable(pocket[0], pocket[1]), "the pocket floor is walkable, that is the point");

        final Pathfinder.Path path = pathfinder.find(stand(open[0], open[1]), pocket[0], pocket[1], RADIUS);

        assertFalse(path.found(), "a walled off target was routed to");
    }

    @Test
    void aSearchGivesUpAtItsNodeBudgetInsteadOfWalkingTheWholeMap() {
        final int[] pocket = insideThePocket();
        final int[] open = openRun(1);

        final Pathfinder.Path path = pathfinder.find(stand(open[0], open[1]), pocket[0], pocket[1], RADIUS);

        assertFalse(path.found());
        assertTrue(walkableColumns() > Pathfinder.MAX_EXPANSIONS,
                "the map has to be bigger than the budget for this case to be about the budget: "
                        + walkableColumns() + " walkable columns against a budget of " + Pathfinder.MAX_EXPANSIONS);
    }

    @Test
    void aSearchFromOrToAColumnNothingCanStandOnFindsNothing() {
        final int[] blocked = firstObstacle();
        final int[] open = openRun(1);

        assertFalse(pathfinder.find(stand(open[0], open[1]), blocked[0], blocked[1], RADIUS).found());
        assertFalse(pathfinder.find(new Pos(blocked[0] + 0.5, MapTerrain.GROUND_HEIGHT, blocked[1] + 0.5),
                open[0], open[1], RADIUS).found());
    }

    /** Whether a unit of the test archetype can walk the straight line between two waypoints. */
    private boolean walkableBetween(Pos from, Pos to) {
        final int samples = (int) Math.ceil(from.distance(to) * 4);
        for (int i = 0; i <= samples; i++) {
            final double at = (double) i / samples;
            final double x = from.x() + (to.x() - from.x()) * at;
            final double z = from.z() + (to.z() - from.z()) * at;
            if (!grid.isWalkable((int) Math.floor(x), (int) Math.floor(z))) return false;
        }
        return true;
    }

    private Pos stand(int x, int z) {
        return new Pos(x + 0.5, grid.surfaceY(x, z), z + 0.5);
    }

    /** The first column of a run of walkable ones, with a clear block on either side of the run. */
    private int[] openRun(int length) {
        for (int z = 1; z < PRESET.sizeZ() - 1; z++) {
            column:
            for (int x = 1; x < PRESET.sizeX() - length - 1; x++) {
                for (int step = -1; step <= length; step++) {
                    if (!grid.isWalkable(x + step, z - 1) || !grid.isWalkable(x + step, z)
                            || !grid.isWalkable(x + step, z + 1)) continue column;
                }
                return new int[]{x, z};
            }
        }
        throw new AssertionError("the generated map has no open run of " + length + " columns");
    }

    /** A column a unit cannot stand on, which the map has by construction (REQ-M2-010). */
    private int[] firstObstacle() {
        for (int x = 8; x < PRESET.sizeX() - 8; x++) {
            for (int z = 8; z < PRESET.sizeZ() - 8; z++) {
                if (!grid.isWalkable(x, z) && !terrain.isInPocketArea(x, z)) return new int[]{x, z};
            }
        }
        throw new AssertionError("the generated map has no obstacle to walk around");
    }

    /** A walkable column of the walled off pocket, which is the map's unreachable area. */
    private int[] insideThePocket() {
        for (int x = 0; x < PRESET.sizeX(); x++) {
            for (int z = 0; z < PRESET.sizeZ(); z++) {
                if (terrain.isInPocketArea(x, z) && grid.isWalkable(x, z)) return new int[]{x, z};
            }
        }
        throw new AssertionError("the generated map has no walled off area");
    }

    private int walkableColumns() {
        int count = 0;
        for (int x = 0; x < PRESET.sizeX(); x++) {
            for (int z = 0; z < PRESET.sizeZ(); z++) {
                if (grid.isWalkable(x, z)) count++;
            }
        }
        return count;
    }
}
