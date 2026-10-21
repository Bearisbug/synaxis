package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class GeneratorIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void loader(boolean data, Env env) {
        var manager = env.process().instance();
        var block = data ? Block.STONE.withNbt(CompoundBinaryTag.builder().putString("key", "value").build()) : Block.STONE;
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fill(block));
        instance.loadChunk(0, 0).join();
        assertEquals(block, instance.getBlock(0, 0, 0));
        assertEquals(block, instance.getBlock(15, 0, 0));
        assertEquals(block, instance.getBlock(0, 15, 0));
        assertEquals(block, instance.getBlock(0, 0, 15));
    }

    @Test
    public void exceptionCatch(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();

        var ref = new AtomicReference<Throwable>();
        env.process().exception().setExceptionHandler(ref::set);

        var exception = new RuntimeException();
        instance.setGenerator(unit -> {
            unit.modifier().fill(Block.STONE);
            throw exception;
        });
        instance.loadChunk(0, 0).join();

        assertSame(exception, ref.get());
    }

    @Test
    public void fillHeightNegative(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(-64, -60, Block.STONE));
        instance.loadChunk(0, 0).join();
        for (int y = -64; y < -60; y++) {
            assertEquals(Block.STONE, instance.getBlock(0, y, 0), "y=" + y);
        }
        for (int y = -60; y < 100; y++) {
            assertEquals(Block.AIR, instance.getBlock(0, y, 0), "y=" + y);
        }
    }

    @Test
    public void fillHeightSingleSectionFull(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 16, Block.GRASS_BLOCK));
        instance.loadChunk(0, 0).join();
        for (int y = 0; y < 16; y++) {
            assertEquals(Block.GRASS_BLOCK, instance.getBlock(0, y, 0), "y=" + y);
        }
    }

    @Test
    public void fillHeightSingleSection(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(4, 5, Block.GRASS_BLOCK));
        instance.loadChunk(0, 0).join();
        for (int y = 0; y < 5; y++) {
            assertEquals(y == 4 ? Block.GRASS_BLOCK : Block.AIR, instance.getBlock(0, y, 0), "y=" + y);
        }
    }

    @Test
    public void fillHeightOverride(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> {
            unit.modifier().fillHeight(0, 39, Block.GRASS_BLOCK);
            unit.modifier().fillHeight(39, 40, Block.STONE);
        });
        instance.loadChunk(0, 0).join();
        for (int y = 0; y < 40; y++) {
            assertEquals(y == 39 ? Block.STONE : Block.GRASS_BLOCK, instance.getBlock(0, y, 0), "y=" + y);
        }
    }

    // fillHeight sets y in [minHeight, maxHeight) of every column of the chunk, the part outside the world cut off
    @ParameterizedTest
    @CsvSource({"1, 2", "1, 16", "0, 16", "15, 17", "1, 47", "-64, -63", "40, 500", "3, 7", "-20, 5"})
    public void fillHeightSetsTheRangeOnly(int minHeight, int maxHeight, Env env) {
        var instance = env.process().instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(minHeight, maxHeight, Block.STONE));
        var chunk = instance.loadChunk(0, 0).join();
        for (int sectionY = chunk.getMinSection(); sectionY < chunk.getMaxSection(); sectionY++) {
            final int sectionMinY = sectionY * 16;
            chunk.getSection(sectionY).blockPalette().getAll((x, y, z, value) -> {
                final int worldY = sectionMinY + y;
                final boolean inRange = worldY >= minHeight && worldY < maxHeight;
                assertEquals(inRange ? Block.STONE.stateId() : Block.AIR.stateId(), value,
                        () -> "at " + x + ", " + worldY + ", " + z);
            });
        }
    }

    @Test
    public void explicitChunkGenerate(Env env) {
        var instance = env.createEmptyInstance();
        Generator generator = unit -> {
            assertTrue(Thread.currentThread().isVirtual());
            unit.modifier().fill(Block.GRASS_BLOCK);
        };
        instance.generateChunk(0, 0, generator).join();
        assertNotNull(instance.getChunk(0, 0));
        assertEquals(Block.GRASS_BLOCK, instance.getBlock(0, 0, 0));
    }

    @Test
    public void explicitChunkGenerateOverride(Env env) {
        var instance = env.createEmptyInstance();
        instance.setGenerator(unit -> unit.modifier().fill(Block.STONE));
        Generator generator = unit -> unit.modifier().fill(Block.GRASS_BLOCK);
        instance.generateChunk(0, 0, generator).join();
        assertNotNull(instance.getChunk(0, 0));
        assertEquals(Block.GRASS_BLOCK, instance.getBlock(0, 0, 0));
    }

    @Test
    public void explicitChunkGenerateLock(Env env) {
        var instance = env.createEmptyInstance();
        DynamicChunk chunk = (DynamicChunk) instance.loadChunk(0, 0).join();
        Generator generator = unit -> {
            chunk.assertWriteLock();
            unit.modifier().fill(Block.GRASS_BLOCK);
        };
        instance.generateChunk(0, 0, generator).join();
        assertEquals(Block.GRASS_BLOCK, instance.getBlock(0, 0, 0));
    }

    @Test
    public void chunkReadLockCannotUpgradeToWriteLock(Env env) {
        var instance = env.createEmptyInstance();
        var chunk = instance.loadChunk(0, 0).join();
        chunk.lockReadLock();
        try {
            assertThrows(IllegalStateException.class, chunk::lockWriteLock);
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * A block set under the read-lock of its chunk throws, instead of waiting for the write-lock the
     * caller keeps from itself, and leaves the instance free: another thread sets a block of another
     * chunk while the read-lock is still held.
     */
    @Test
    public void aBlockSetUnderTheReadLockOfItsChunkThrows(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();
        chunk.lockReadLock();
        try {
            assertThrows(IllegalStateException.class, () -> instance.setBlock(0, 41, 0, Block.GOLD_BLOCK));
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> CompletableFuture.runAsync(() -> instance.setBlock(16, 41, 0, Block.GOLD_BLOCK)).join());
        } finally {
            chunk.unlockReadLock();
        }
        assertEquals(Block.AIR, instance.getBlock(0, 41, 0));
        assertEquals(Block.GOLD_BLOCK, instance.getBlock(16, 41, 0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void loaderExceptionCompletesChunkFuture(boolean parallel, Env env) {
        var exception = new RuntimeException("loader failure");
        env.process().exception().setExceptionHandler(_ -> {
        });
        ChunkLoader chunkLoader = new ChunkLoader() {
            @Override
            public Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
                throw exception;
            }

            @Override
            public void saveChunk(Chunk chunk) {
            }

            @Override
            public boolean supportsParallelLoading() {
                return parallel;
            }
        };
        var instance = env.createEmptyInstance(chunkLoader);

        var thrown = assertThrows(CompletionException.class, () -> instance.loadChunk(0, 0).join());
        assertSame(exception, thrown.getCause());
        assertNull(instance.getChunk(0, 0));
    }

    @Test
    public void concurrentChunkLoadsComplete(Env env) {
        var instance = env.createEmptyInstance();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[64];
        for (int i = 0; i < futures.length; i++) {
            futures[i] = instance.loadChunk(i & 7, i >> 3);
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CompletableFuture.allOf(futures).join());
        assertEquals(futures.length, instance.getChunks().size());
    }

    @Test
    public void explicitChunkGeneratePacket(Env env) {
        var instance = env.createEmptyInstance();
        var connection = env.createConnection();
        connection.connect(instance, Pos.ZERO);
        Generator generator = unit -> unit.modifier().fill(Block.GRASS_BLOCK);
        var tracker = connection.trackIncoming(ChunkDataPacket.class);
        instance.generateChunk(0, 0, generator).join();
        tracker.assertAny();
    }
}
