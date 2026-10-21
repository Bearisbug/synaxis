package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;
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

    private record Computation(String name, WorldView view, int[] area) {
    }

    /** Each world over the three areas in turn: the size of the area changes from one computation to the next. */
    @Test
    public void oneWorkspaceGivesEachComputationTheLightOfANewOne() throws Exception {
        final List<Computation> computations = new ArrayList<>();
        final List<WorldView> views = worlds();
        for (int v = 0; v < views.size(); v++) {
            for (int[] area : AREAS) computations.add(new Computation("view " + v, views.get(v), area));
        }
        assertLikeNewWorkspaces(computations);
    }

    /**
     * One area over all the worlds in a row, as a relight thread computes one 5x5 after the other: the same size
     * each time, so the workspace clears its tables instead of making new ones. The worlds go from the scenes to the
     * four with chunk (0, 0) missing and to one of air only, then again through views that report an all-air
     * section without writing the array, as a palette view does: a section with blocks in one computation is
     * missing or air in the next.
     */
    @Test
    public void oneWorkspaceOverOneAreaInARowGivesEachTheLightOfANewOne() throws Exception {
        final List<WorldView> views = worlds();
        views.add(new ArrayWorldView(LightScenes.LOAD_RADIUS, LightScenes.MIN_Y, LightScenes.HEIGHT));
        final int plain = views.size();
        for (int v = 0; v < plain; v++) views.add(new AirUnwritten(views.get(v)));
        final List<Computation> computations = new ArrayList<>();
        for (int[] area : AREAS) {
            for (int v = 0; v < views.size(); v++) computations.add(new Computation("view " + v, views.get(v), area));
        }
        assertLikeNewWorkspaces(computations);
    }

    /** The parity scenes, then the four scenes around missing chunk (0, 0). */
    private static List<WorldView> worlds() throws Exception {
        final List<WorldView> views = new ArrayList<>();
        for (String scene : LightScenes.names()) views.add(LightScenes.view(LightScenes.parse(scene)));
        for (int turns = 0; turns < 4; turns++) views.add(LightEngineTest.pastAMissingChunk(turns));
        return views;
    }

    private static void assertLikeNewWorkspaces(List<Computation> computations) {
        final LightEngine.Workspace workspace = new LightEngine.Workspace();
        final List<LightResult> reused = new ArrayList<>();
        for (Computation c : computations) {
            reused.add(LightEngine.computeArea(c.view(), c.area()[0], c.area()[1], c.area()[2], c.area()[3], workspace));
        }

        // Compared once all are computed: a result sharing an array with the workspace would hold later light
        for (int k = 0; k < computations.size(); k++) {
            final Computation c = computations.get(k);
            final int[] area = c.area();
            final LightResult fresh = LightEngine.computeArea(c.view(), area[0], area[1], area[2], area[3]);
            final LightResult result = reused.get(k);
            final int minSection = Math.floorDiv(c.view().minY(), 16);
            for (int chunkX = area[0]; chunkX <= area[2]; chunkX++) {
                for (int chunkZ = area[1]; chunkZ <= area[3]; chunkZ++) {
                    for (int sectionY = minSection; sectionY < minSection + (c.view().height() >> 4); sectionY++) {
                        final String at = "computation " + k + " (" + c.name() + ", area " + area[0] + "," + area[1]
                                + ".." + area[2] + "," + area[3] + "), section " + chunkX + "," + sectionY + "," + chunkZ;
                        assertArrayEquals(fresh.sky(chunkX, sectionY, chunkZ), result.sky(chunkX, sectionY, chunkZ),
                                "sky of " + at);
                        assertArrayEquals(fresh.block(chunkX, sectionY, chunkZ), result.block(chunkX, sectionY, chunkZ),
                                "block of " + at);
                    }
                }
            }
        }
    }

    /** A view whose {@link #section} returns false for a section of air and leaves the array as it found it. */
    private record AirUnwritten(WorldView view) implements WorldView {
        @Override
        public int minY() {
            return view.minY();
        }

        @Override
        public int height() {
            return view.height();
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return view.hasChunk(chunkX, chunkZ);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return view.getBlock(x, y, z);
        }

        @Override
        public boolean section(int chunkX, int sectionMinY, int chunkZ, char[] states) {
            final char[] ids = new char[4096];
            if (!view.section(chunkX, sectionMinY, chunkZ, ids)) return false;
            System.arraycopy(ids, 0, states, 0, ids.length);
            return true;
        }
    }
}
