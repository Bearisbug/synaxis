package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.world.biome.Biome;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class GeneratorForkIntegrationTest {

    @Test
    public void local(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        var block = Block.STONE;
        instance.setGenerator(unit -> {
            var u = unit.fork(unit.absoluteStart(), unit.absoluteEnd());
            assertEquals(unit.absoluteStart(), u.absoluteStart());
            assertEquals(unit.absoluteEnd(), u.absoluteEnd());
            u.modifier().setRelative(0, 0, 0, Block.STONE);
        });
        instance.loadChunk(0, 0).join();
        assertEquals(block, instance.getBlock(0, -64, 0));
    }

    @Test
    public void size(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        // Set the Generator
        instance.setGenerator(unit -> {
            Point start = unit.absoluteStart();
            GenerationUnit fork = unit.fork(start, start.add(18, 18, 18));
            assertDoesNotThrow(() -> fork.modifier().setBlock(start.add(17, 17, 17), Block.STONE));
        });
        // Load the chunks
        instance.loadChunk(0, 0).join();
        instance.setGenerator(null);
        instance.loadChunk(1, 1).join();
        assertEquals(Block.STONE, instance.getBlock(17, -64 + 17, 17));
    }

    @Test
    public void signal(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        var block = Block.STONE;
        instance.setGenerator(unit -> {
            var u = unit.fork(unit.absoluteStart(), unit.absoluteEnd().add(16, 0, 16));
            assertEquals(unit.absoluteStart(), u.absoluteStart());
            assertEquals(unit.absoluteEnd().add(16, 0, 16), u.absoluteEnd());
            u.modifier().setRelative(16, 0, 0, Block.STONE);
            u.modifier().setRelative(16, 33, 0, Block.STONE);
        });
        instance.loadChunk(0, 0).join();
        instance.setGenerator(null);
        instance.loadChunk(1, 0).join();
        assertEquals(block, instance.getBlock(16, -64, 0));
        assertEquals(block, instance.getBlock(16, -31, 0));
    }

    @Test
    public void air(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> {
            var u = unit.fork(unit.absoluteStart(), unit.absoluteEnd().add(16, 0, 16));
            u.modifier().setRelative(16, 39 + 64, 0, Block.AIR);
        });
        instance.loadChunk(0, 0).join();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        instance.loadChunk(1, 0).join();
        assertEquals(Block.AIR, instance.getBlock(16, 39, 0));
    }

    @Test
    public void fillHeight(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> {
            var u = unit.fork(unit.absoluteStart(), unit.absoluteEnd().add(16, 0, 16));
            u.modifier().fillHeight(0, 40, Block.STONE);
        });
        instance.loadChunk(0, 0).join();
        instance.setGenerator(null);
        instance.loadChunk(1, 0).join();
        for (int y = 0; y < 40; y++) {
            assertEquals(Block.STONE, instance.getBlock(16, y, 0), "y=" + y);
        }
    }

    // A fork reaching outside the world height cannot be applied, dropping it must not abort
    // the forks behind it
    @Test
    public void outsideWorldHeight(Env env) {
        var manager = env.process().instance();
        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> {
            final int aboveWorld = unit.absoluteEnd().blockY() + 5;
            unit.fork(setter -> setter.setBlock(0, aboveWorld, 0, Block.STONE));
            unit.fork(setter -> setter.setBlock(1, 0, 1, Block.GLASS));
        });
        instance.loadChunk(0, 0).join();
        assertEquals(Block.GLASS, instance.getBlock(1, 0, 1), "an out of bounds fork aborted the generation");
    }

    @Test
    public void biome(Env env) {
        var manager = env.process().instance();

        var instance = manager.createInstanceContainer();
        instance.setGenerator(unit -> {
            var u = unit.fork(unit.absoluteStart(), unit.absoluteEnd().add(16, 0, 16));
            assertThrows(IllegalStateException.class, () -> u.modifier().setBiome(16, 0, 0, Biome.PLAINS));
            assertThrows(IllegalStateException.class, () -> u.modifier().fillBiome(Biome.PLAINS));
        });
        instance.loadChunk(0, 0).join();
    }

    // A fork made on a section of subdivide() lands in the neighbour like a fork made on the chunk unit
    @Test
    public void aForkOfASubdividedSectionLands(Env env) {
        var instance = env.process().instance().createInstanceContainer();
        instance.setGenerator(unit -> {
            if (unit.absoluteStart().chunkX() != 0 || unit.absoluteStart().chunkZ() != 0) return;
            unit.subdivide().getFirst().fork(setter -> setter.setBlock(16, -64, 0, Block.STONE));
        });
        instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();
        assertEquals(Block.STONE, instance.getBlock(16, -64, 0));
    }

    // Chunk 1 is past its own processFork, in onGenerate, and not cached yet when chunk 0 forks into it:
    // the fork is kept for it and lands once it is cached
    @Test
    public void aForkIntoAChunkBetweenItsGenerationAndItsCachingLands(Env env) throws InterruptedException {
        var instance = env.process().instance().createInstanceContainer();
        final CountDownLatch generating = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        instance.setChunkSupplier((owner, x, z) -> new DynamicChunk(owner, x, z) {
            @Override
            public void onGenerate() {
                if (getChunkX() != 1 || getChunkZ() != 0) return;
                generating.countDown();
                try {
                    boolean _ = release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        instance.setGenerator(unit -> {
            if (unit.absoluteStart().chunkX() != 0 || unit.absoluteStart().chunkZ() != 0) return;
            unit.fork(setter -> setter.setBlock(16, -64, 0, Block.STONE));
        });

        final CompletableFuture<Chunk> neighbour = instance.loadChunk(1, 0);
        assertTrue(generating.await(10, TimeUnit.SECONDS), "chunk 1 reached onGenerate");
        instance.loadChunk(0, 0).join();
        release.countDown();
        neighbour.join();

        assertEquals(Block.STONE, instance.getBlock(16, -64, 0));
    }

    // The same fork places a block whose handler throws on placement: like a throwing generator, the
    // exception goes to the exception handler and chunk 1 loads anyway
    @Test
    public void aThrowingForkIntoAChunkBetweenItsGenerationAndItsCachingLetsItLoad(Env env) throws InterruptedException {
        var instance = env.process().instance().createInstanceContainer();
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        final CountDownLatch generating = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        instance.setChunkSupplier((owner, x, z) -> new DynamicChunk(owner, x, z) {
            @Override
            public void onGenerate() {
                if (getChunkX() != 1 || getChunkZ() != 0) return;
                generating.countDown();
                try {
                    boolean _ = release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            protected void onLoad() {
                if (getChunkX() == 1 && getChunkZ() == 0) loads.incrementAndGet();
            }
        });
        final BlockHandler throwing = new BlockHandler() {
            @Override
            public void onPlace(Placement placement) {
                throw new IllegalStateException("a failing placement");
            }

            @Override
            public Key getKey() {
                return Key.key("test:throwing");
            }
        };
        instance.setGenerator(unit -> {
            if (unit.absoluteStart().chunkX() != 0 || unit.absoluteStart().chunkZ() != 0) return;
            unit.fork(setter -> setter.setBlock(16, -64, 0, Block.STONE.withHandler(throwing)));
        });

        final CompletableFuture<Chunk> neighbour = instance.loadChunk(1, 0);
        assertTrue(generating.await(10, TimeUnit.SECONDS), "chunk 1 reached onGenerate");
        instance.loadChunk(0, 0).join();
        release.countDown();
        final Chunk chunk = neighbour.join();

        assertSame(chunk, instance.getChunk(1, 0));
        assertEquals(1, loads.get(), "onLoad of chunk 1");
        assertEquals(1, reported.size(), "exceptions reported: " + reported);
        assertEquals("a failing placement", reported.getFirst().getMessage());
    }
}
