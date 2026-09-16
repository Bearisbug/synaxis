package io.github.bearisbug.synaxis.game.rts;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The navigation mesh generated with the map (REQ-M2-010): it has to carry obstacles and one
 * region nothing can walk into, which is what gives the pathfinding of §26 stage 5 a target that
 * has to answer {@code no-path}.
 */
class NavGridTest {

    private final MapPreset preset = MapPreset.SMALL;
    private final MapTerrain terrain = new MapTerrain(preset);
    private final NavGrid grid = NavGrid.of(terrain);

    @Test
    void columnsOutsideTheMapAreNotWalkable() {
        assertFalse(grid.isWalkable(-1, 0));
        assertFalse(grid.isWalkable(0, preset.sizeZ()));
    }

    @Test
    void theStandingHeightIsTheGroundAndTheObstacleOnTopOfIt() {
        final int x = walkableColumnX();
        assertEquals(MapTerrain.GROUND_HEIGHT, grid.surfaceY(x, 0));

        final int[] obstacle = obstacleColumn();
        assertEquals(MapTerrain.GROUND_HEIGHT + MapTerrain.OBSTACLE_HEIGHT,
                grid.surfaceY(obstacle[0], obstacle[1]));
    }

    @Test
    void theMapCarriesObstacles() {
        int obstacles = 0;
        for (int x = 0; x < preset.sizeX(); x++) {
            for (int z = 0; z < preset.sizeZ(); z++) {
                if (!grid.isWalkable(x, z)) obstacles++;
            }
        }
        assertTrue(obstacles > 0, "the generated map is empty ground");
        assertTrue(obstacles < preset.sizeX() * preset.sizeZ() / 10,
                "obstacles=" + obstacles + " leaves too little of the map walkable");
    }

    @Test
    void thePocketIsWalkableAndUnreachable() {
        final BitSet reached = floodFillFromTheMiddle();

        int insidePocket = 0;
        for (int x = 0; x < preset.sizeX(); x++) {
            for (int z = 0; z < preset.sizeZ(); z++) {
                if (!terrain.isInPocketArea(x, z) || !grid.isWalkable(x, z)) continue;
                insidePocket++;
                assertFalse(reached.get(z * preset.sizeX() + x),
                        "column " + x + "," + z + " of the walled off pocket was reachable");
            }
        }
        assertTrue(insidePocket > 0, "the pocket has no walkable column, nothing would ever path into it");
    }

    @Test
    void theRestOfTheMapIsOneConnectedArea() {
        final BitSet reached = floodFillFromTheMiddle();

        int walkable = 0;
        for (int x = 0; x < preset.sizeX(); x++) {
            for (int z = 0; z < preset.sizeZ(); z++) {
                if (grid.isWalkable(x, z) && !terrain.isInPocketArea(x, z)) walkable++;
            }
        }
        assertEquals(walkable, reached.cardinality(),
                "the scattered obstacles cut the map into pieces instead of leaving it connected");
    }

    private BitSet floodFillFromTheMiddle() {
        final BitSet reached = new BitSet(preset.sizeX() * preset.sizeZ());
        final Deque<int[]> queue = new ArrayDeque<>();
        final int startX = preset.sizeX() / 2;
        final int startZ = preset.sizeZ() / 2;
        assertTrue(grid.isWalkable(startX, startZ), "the middle of the map is not walkable");
        reached.set(startZ * preset.sizeX() + startX);
        queue.add(new int[]{startX, startZ});
        final int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            final int[] column = queue.poll();
            for (int[] step : steps) {
                final int x = column[0] + step[0];
                final int z = column[1] + step[1];
                if (!grid.isWalkable(x, z)) continue;
                final int index = z * preset.sizeX() + x;
                if (reached.get(index)) continue;
                reached.set(index);
                queue.add(new int[]{x, z});
            }
        }
        return reached;
    }

    private int[] obstacleColumn() {
        for (int x = 0; x < preset.sizeX(); x++) {
            for (int z = 0; z < preset.sizeZ(); z++) {
                if (!grid.isWalkable(x, z)) return new int[]{x, z};
            }
        }
        throw new AssertionError("the map carries no obstacle at all");
    }

    private int walkableColumnX() {
        for (int x = 0; x < preset.sizeX(); x++) {
            if (grid.isWalkable(x, 0)) return x;
        }
        throw new AssertionError("the first row of the map is solid");
    }
}
