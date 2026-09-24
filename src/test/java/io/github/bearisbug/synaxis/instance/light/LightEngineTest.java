package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class LightEngineTest {
    private static final int RADIUS = 4;

    /** Stone up to y 0 with holes, a random canopy above it and torches scattered through both. */
    private static ArrayWorldView terrain() {
        final ArrayWorldView view = new ArrayWorldView(RADIUS, -64, 384);
        final Random random = new Random(7);
        final Block[] canopy = {Block.OAK_LEAVES, Block.GLASS, Block.WATER, Block.OAK_SLAB, Block.STONE, Block.GLOWSTONE};
        final int min = -RADIUS * 16, max = RADIUS * 16 + 15;
        for (int x = min; x <= max; x++) {
            for (int z = min; z <= max; z++) {
                for (int y = -64; y <= 0; y++) {
                    if (random.nextInt(10) != 0) view.set(x, y, z, Block.STONE);
                }
                if (random.nextInt(3) == 0) view.set(x, 12, z, canopy[random.nextInt(canopy.length)]);
                if (random.nextInt(40) == 0) view.set(x, 1 + random.nextInt(10), z, Block.TORCH);
            }
        }
        return view;
    }

    @Test
    public void aTorchInOpenAirFadesOneLevelPerBlock() {
        final ArrayWorldView view = new ArrayWorldView(1, -64, 384);
        view.set(8, 70, 8, Block.TORCH);
        final LightResult result = LightEngine.computeArea(view, 0, 0, 0, 0);
        assertEquals(14, result.blockLevel(8, 70, 8));
        assertEquals(13, result.blockLevel(9, 70, 8));
        assertEquals(10, result.blockLevel(8, 74, 8));
        assertEquals(1, result.blockLevel(8, 83, 8));
        assertEquals(15, result.skyLevel(8, -64, 8));
        assertSame(LightCompute.CONTENT_FULLY_LIT, result.sky(0, 0, 0));
        assertSame(LightCompute.EMPTY_CONTENT, result.block(0, 0, 0));
    }

    /**
     * No instance behind the view and no lock taken: two threads light two disjoint areas of the same view at
     * once and get what one thread gets for each on its own.
     */
    @Test
    public void disjointAreasComputeConcurrentlyWithoutAnInstance() {
        final ArrayWorldView view = terrain();
        final LightResult westAlone = LightEngine.computeArea(view, -3, -3, -1, 3);
        final LightResult eastAlone = LightEngine.computeArea(view, 0, -3, 3, 3);

        final CountDownLatch start = new CountDownLatch(1);
        final CompletableFuture<LightResult> west;
        final CompletableFuture<LightResult> east;
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            west = CompletableFuture.supplyAsync(() -> afterLatch(start, () -> LightEngine.computeArea(view, -3, -3, -1, 3)), threads);
            east = CompletableFuture.supplyAsync(() -> afterLatch(start, () -> LightEngine.computeArea(view, 0, -3, 3, 3)), threads);
            start.countDown();
        }

        assertSameLight(westAlone, west.join(), -3, -3, -1, 3);
        assertSameLight(eastAlone, east.join(), 0, -3, 3, 3);
    }

    /** relightArea is the 3x3 around its chunk, and a larger area gives the same light for those chunks. */
    @Test
    public void relightAreaIsTheThreeByThreeAroundTheChunk() {
        final ArrayWorldView view = terrain();
        assertSameLight(LightEngine.computeArea(view, -3, -3, 3, 3), LightEngine.relightArea(view, 1, -1), 0, -2, 2, 0);
    }

    private static LightResult afterLatch(CountDownLatch latch, Supplier<LightResult> computation) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return computation.get();
    }

    private static void assertSameLight(LightResult expected, LightResult actual, int cxMin, int czMin, int cxMax, int czMax) {
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                for (int sectionY = -4; sectionY < 20; sectionY++) {
                    final String where = cx + "," + sectionY + "," + cz;
                    assertArrayEquals(expected.sky(cx, sectionY, cz), actual.sky(cx, sectionY, cz), "sky " + where);
                    assertArrayEquals(expected.block(cx, sectionY, cz), actual.block(cx, sectionY, cz), "block " + where);
                }
            }
        }
    }
}
