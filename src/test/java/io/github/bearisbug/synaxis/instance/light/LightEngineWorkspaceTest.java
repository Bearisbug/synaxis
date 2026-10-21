package io.github.bearisbug.synaxis.instance.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@link LightEngine.Workspace}: one workspace taken through computations over different worlds and areas gives
 * each of them, byte for byte, the light a new workspace gives it, so nothing of one computation is left for the
 * next; and a result keeps its light while the workspace computes the ones after it.
 */
public class LightEngineWorkspaceTest {
    /** chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ: the 3x3 of a relight, one chunk, and a 2x2 in between. */
    private static final int[][] AREAS = {{-1, -1, 1, 1}, {0, 0, 0, 0}, {-1, 0, 0, 1}};

    @Test
    public void oneWorkspaceGivesEachComputationTheLightOfANewOne() throws Exception {
        final List<WorldView> views = new ArrayList<>();
        for (String scene : LightScenes.names()) views.add(LightScenes.view(LightScenes.parse(scene)));
        for (int turns = 0; turns < 4; turns++) views.add(LightEngineTest.pastAMissingChunk(turns));

        final LightEngine.Workspace workspace = new LightEngine.Workspace();
        final List<LightResult> reused = new ArrayList<>();
        for (WorldView view : views) {
            for (int[] area : AREAS) reused.add(LightEngine.computeArea(view, area[0], area[1], area[2], area[3], workspace));
        }

        // Compared once all are computed: a result sharing an array with the workspace would hold later light
        int next = 0;
        for (int v = 0; v < views.size(); v++) {
            final WorldView view = views.get(v);
            for (int[] area : AREAS) {
                final LightResult fresh = LightEngine.computeArea(view, area[0], area[1], area[2], area[3]);
                final LightResult result = reused.get(next++);
                final int minSection = Math.floorDiv(view.minY(), 16);
                for (int chunkX = area[0]; chunkX <= area[2]; chunkX++) {
                    for (int chunkZ = area[1]; chunkZ <= area[3]; chunkZ++) {
                        for (int sectionY = minSection; sectionY < minSection + (view.height() >> 4); sectionY++) {
                            final String at = "view " + v + ", area " + area[0] + "," + area[1] + ".." + area[2] + ","
                                    + area[3] + ", section " + chunkX + "," + sectionY + "," + chunkZ;
                            assertArrayEquals(fresh.sky(chunkX, sectionY, chunkZ), result.sky(chunkX, sectionY, chunkZ),
                                    "sky of " + at);
                            assertArrayEquals(fresh.block(chunkX, sectionY, chunkZ), result.block(chunkX, sectionY, chunkZ),
                                    "block of " + at);
                        }
                    }
                }
            }
        }
    }
}
