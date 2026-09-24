package io.github.bearisbug.synaxis.thread;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code docs/DESIGN-PROD.md} {@code API-PROD-016}: the grid gives every chunk of a cell of
 * {@code 2^exponent} by {@code 2^exponent} chunks the same thread id, and never moves a chunk.
 */
public class ThreadProviderGridTest {

    record ChunkAt(int x, int z) {
    }

    private static final ThreadProvider<ChunkAt> GRID = ThreadProvider.grid(4, ChunkAt::x, ChunkAt::z);

    @Test
    void theChunksOfOneCellShareAThreadId() {
        final int origin = GRID.findThread(new ChunkAt(0, 0));
        assertEquals(origin, GRID.findThread(new ChunkAt(15, 15)));
        assertEquals(origin, GRID.findThread(new ChunkAt(7, 0)));
        assertNotEquals(origin, GRID.findThread(new ChunkAt(16, 0)));
        assertNotEquals(origin, GRID.findThread(new ChunkAt(0, 16)));
        // Negative coordinates round down: chunk -1 is in cell -1, not in cell 0
        assertNotEquals(origin, GRID.findThread(new ChunkAt(-1, 0)));
        assertEquals(GRID.findThread(new ChunkAt(-16, -16)), GRID.findThread(new ChunkAt(-1, -1)));
    }

    @Test
    void theIdIsThreeTimesTheCellXPlusTheCellZ() {
        assertEquals(0, GRID.findThread(new ChunkAt(0, 0)));
        assertEquals(3, GRID.findThread(new ChunkAt(16, 0)));
        assertEquals(1, GRID.findThread(new ChunkAt(0, 16)));
        assertEquals(3 * 2 + 3, ThreadProvider.grid(2, ChunkAt::x, ChunkAt::z).findThread(new ChunkAt(8, 12)));
    }

    /**
     * The shards run cores − 2 threads: 6 on the 8 core machines of the design, 10 on a 12 core one.
     * Players crowd into neighbouring cells, so the cells around one must not pile onto one thread.
     */
    @Test
    void neighbouringCellsGetDifferentThreads() {
        for (int threads : new int[]{6, 10}) {
            for (int cellX = -3; cellX <= 3; cellX++) {
                for (int cellZ = -3; cellZ <= 3; cellZ++) {
                    final int thread = Math.floorMod(GRID.findThread(new ChunkAt(cellX * 16, cellZ * 16)), threads);
                    final int reach = threads >= 10 ? 2 : 1;
                    for (int dx = -reach; dx <= reach; dx++) {
                        for (int dz = -reach; dz <= reach; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            final ChunkAt other = new ChunkAt((cellX + dx) * 16, (cellZ + dz) * 16);
                            assertNotEquals(thread, Math.floorMod(GRID.findThread(other), threads),
                                    "cells (" + cellX + ", " + cellZ + ") and (" + (cellX + dx) + ", " + (cellZ + dz)
                                            + ") on " + threads + " threads");
                        }
                    }
                }
            }
        }
    }

    @Test
    void aChunkNeverChangesThread() {
        assertEquals(ThreadProvider.RefreshType.NEVER, GRID.refreshType());
    }

    @Test
    void theExponentIsAShiftOfAnInt() {
        assertThrows(IllegalArgumentException.class, () -> ThreadProvider.grid(-1, ChunkAt::x, ChunkAt::z));
        assertThrows(IllegalArgumentException.class, () -> ThreadProvider.grid(32, ChunkAt::x, ChunkAt::z));
        assertEquals(1, ThreadProvider.grid(0, ChunkAt::x, ChunkAt::z).findThread(new ChunkAt(0, 1)));
    }
}
