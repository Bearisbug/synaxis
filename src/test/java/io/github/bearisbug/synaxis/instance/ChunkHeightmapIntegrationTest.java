package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.utils.MathUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class ChunkHeightmapIntegrationTest {
    @Test
    public void testChunkHeightmap(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var chunk = instance.getChunk(0, 0);

        int heightmap = chunk.motionBlockingHeightmap().getHeight(0, 0);
        assertEquals(39, heightmap);
    }

    // The column of getHeight is chunk-local: a world coordinate is refused instead of reading another column
    @Test
    public void getHeightRejectsWorldCoordinates(Env env) {
        var chunk = env.createFlatInstance().loadChunk(1, 0).join();
        var heightmap = chunk.motionBlockingHeightmap();

        assertThrows(IllegalArgumentException.class, () -> heightmap.getHeight(20, 0));
        assertThrows(IllegalArgumentException.class, () -> heightmap.getHeight(0, 16));
        assertThrows(IllegalArgumentException.class, () -> heightmap.getHeight(-1, 0));
        assertEquals(39, heightmap.getHeight(15, 15));
    }

    @Test
    public void emptyColumnIsBelowMinY(Env env) {
        var instance = env.createEmptyInstance();
        var chunk = instance.loadChunk(0, 0).join();
        final int belowWorld = instance.getCachedDimensionType().minY() - 1;

        assertEquals(belowWorld, chunk.motionBlockingHeightmap().getHeight(0, 0));
        assertEquals(belowWorld, chunk.worldSurfaceHeightmap().getHeight(15, 15));
    }

    // A world saved with a different dimension height packs its heightmap into fewer longs than
    // this dimension needs, that must degrade to a recomputation instead of throwing and
    // taking the whole chunk down with it
    @Test
    public void tooShortHeightmapDataIsRecomputed(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var chunk = instance.getChunk(0, 0);
        var heightmap = chunk.motionBlockingHeightmap();

        assertDoesNotThrow(() -> heightmap.loadFrom(new long[1]));
        assertEquals(39, heightmap.getHeight(0, 0));
    }

    @Test
    public void heightMapPlaceTest(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var chunk = instance.getChunk(0, 0);

        {
            instance.setBlock(0, 40, 0, Block.STONE);
            int heightmap = chunk.motionBlockingHeightmap().getHeight(0, 0);
            assertEquals(40, heightmap);
        }

        {
            instance.setBlock(0, 45, 0, Block.STONE);
            int heightmap = chunk.motionBlockingHeightmap().getHeight(0, 0);
            assertEquals(45, heightmap);
        }
    }

    @Test
    public void motionBlockingFluidTest(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var chunk = instance.getChunk(0, 0);
        var heightmap = chunk.motionBlockingHeightmap();

        // Inherently waterlogged blocks count even without a "waterlogged" property
        instance.setBlock(0, 45, 0, Block.KELP);
        assertEquals(45, heightmap.getHeight(0, 0));

        instance.setBlock(1, 45, 0, Block.SEAGRASS);
        assertEquals(45, heightmap.getHeight(1, 0));

        // Waterlogged state of a block without collision counts, dry state does not
        instance.setBlock(2, 45, 0, Block.GLOW_LICHEN.withProperty("waterlogged", "true"));
        assertEquals(45, heightmap.getHeight(2, 0));

        instance.setBlock(3, 45, 0, Block.GLOW_LICHEN);
        assertEquals(39, heightmap.getHeight(3, 0));

        // Solid but not motion blocking
        instance.setBlock(4, 45, 0, Block.COBWEB);
        assertEquals(39, heightmap.getHeight(4, 0));

        instance.setBlock(5, 45, 0, Block.BAMBOO_SAPLING);
        assertEquals(39, heightmap.getHeight(5, 0));
    }

    @Test
    public void heightMapRemoveTest(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var chunk = instance.getChunk(0, 0);

        {
            instance.setBlock(0, 45, 0, Block.STONE);
            int heightmap = chunk.motionBlockingHeightmap().getHeight(0, 0);
            assertEquals(45, heightmap);
        }

        {
            instance.setBlock(0, 45, 0, Block.AIR);
            int heightmap = chunk.motionBlockingHeightmap().getHeight(0, 0);
            assertEquals(39, heightmap);
        }
    }

    // Blocks written to the palette directly, then announced with Chunk#invalidate, as a plugin or
    // Instance#invalidateSection does: the heightmaps are computed again on the next read
    @Test
    public void anInvalidatedChunkComputesItsHeightmapsAgain(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        assertEquals(39, chunk.motionBlockingHeightmap().getHeight(0, 0));

        chunk.lockWriteLock();
        try {
            // World y 61 is local y 13 of section 3
            chunk.getSection(3).blockPalette().set(0, 13, 0, Block.STONE.stateId());
            chunk.invalidate();
        } finally {
            chunk.unlockWriteLock();
        }

        assertEquals(61, chunk.motionBlockingHeightmap().getHeight(0, 0), "motion blocking after the invalidation");
        assertEquals(61, chunk.worldSurfaceHeightmap().getHeight(0, 0), "world surface after the invalidation");
        assertEquals(39, chunk.motionBlockingHeightmap().getHeight(1, 0), "a column the change did not touch");
    }

    // Instance#generateChunk on a loaded chunk writes its palettes: the heightmap and the chunk packet follow
    @Test
    public void aRegeneratedChunkSendsItsNewHeightmap(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        assertEquals(39, chunk.motionBlockingHeightmap().getHeight(0, 0));

        instance.generateChunk(0, 0, unit -> unit.modifier().fillHeight(0, 50, Block.STONE)).join();

        // The packet first: it computes the heightmap itself
        final ChunkDataPacket packet = (ChunkDataPacket) ((CachedPacket) chunk.getFullDataPacket()).packet();
        final long[] motionBlocking = packet.chunkData().heightmaps().get(Heightmap.Type.MOTION_BLOCKING);
        final int bits = MathUtils.bitsToRepresent(instance.getCachedDimensionType().height());
        final int belowWorld = instance.getCachedDimensionType().minY() - 1;
        assertEquals(49, (int) (motionBlocking[0] & ((1L << bits) - 1)) + belowWorld, "column (0, 0) in the chunk packet");
        assertEquals(49, chunk.motionBlockingHeightmap().getHeight(0, 0));
    }

    // getHeightmaps computes the stale heightmaps, a write: the chunk packet calls it under the write-lock
    @Test
    public void theHeightmapsOfThePacketNeedTheWriteLock(Env env) {
        var chunk = (DynamicChunk) env.createFlatInstance().loadChunk(0, 0).join();
        chunk.invalidate();
        chunk.lockReadLock();
        try {
            assertThrows(IllegalStateException.class, chunk::getHeightmaps);
        } finally {
            chunk.unlockReadLock();
        }
        chunk.lockWriteLock();
        try {
            assertEquals(2, chunk.getHeightmaps().size());
        } finally {
            chunk.unlockWriteLock();
        }
    }

    // A column changed before a computation started, read on another thread while that computation has not reached
    // it yet: the reader waits for the computation instead of reading the height from before the change
    @Test
    @Timeout(30)
    public void aReaderDuringAComputationWaitsForIt(Env env) throws Exception {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        final Thread test = Thread.currentThread();
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        var heightmap = new Heightmap(chunk) {
            @Override
            public Heightmap.Type type() {
                return Heightmap.Type.MOTION_BLOCKING;
            }

            // The first computation off the test thread stops at its first block until resume
            @Override
            protected boolean checkBlock(Block block) {
                if (Thread.currentThread() != test && stopped.getCount() != 0) {
                    stopped.countDown();
                    try {
                        resume.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return !block.air();
            }
        };
        assertEquals(39, heightmap.getHeight(5, 5));
        instance.setBlock(5, 50, 5, Block.STONE);
        heightmap.invalidate();

        final FutureTask<Integer> compute = new FutureTask<>(() -> heightmap.getHeight(0, 0));
        Thread.ofPlatform().daemon().name("heightmap-compute").start(compute);
        try {
            stopped.await();
            final FutureTask<Integer> read = new FutureTask<>(() -> heightmap.getHeight(5, 5));
            final Thread reader = Thread.ofPlatform().daemon().name("heightmap-read").start(read);
            // The reader either returns at once or waits to enter the monitor the computation holds
            while (reader.isAlive() && reader.getState() != Thread.State.BLOCKED) Thread.onSpinWait();
            resume.countDown();
            assertEquals(50, read.get(), "the column changed before the computation");
            assertEquals(39, compute.get());
        } finally {
            resume.countDown();
        }
    }
}
