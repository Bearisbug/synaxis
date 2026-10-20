package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@EnvTest
public class ChunkLoaderSaveIntegrationTest {

    // A failing saveChunk must still release its Phaser party, otherwise the saving thread never returns
    @Test
    public void parallelSaveReturnsWhenSaveChunkThrows(Env env) {
        AtomicReference<Throwable> handled = new AtomicReference<>();
        env.process().exception().setExceptionHandler(handled::set);

        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        var loader = new ChunkLoader() {
            @Override
            public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
                return null;
            }

            @Override
            public void saveChunk(Chunk chunk) {
                throw new IllegalStateException("save failure");
            }

            @Override
            public boolean supportsParallelSaving() {
                return true;
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> loader.saveChunks(List.of(chunk)),
                "saveChunks never returned after saveChunk threw");
        assertInstanceOf(IllegalStateException.class, handled.get(), "the save failure was not reported");
    }

    // More saves in flight at once than a single Phaser has parties for (65535): every one of them is saved
    @Test
    public void parallelSaveHandlesMoreThanAPhaserHolds(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        AtomicInteger saved = new AtomicInteger();
        CountDownLatch allStarted = new CountDownLatch(70_000);
        var loader = new ChunkLoader() {
            @Override
            public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
                return null;
            }

            @Override
            public void saveChunk(Chunk chunk) {
                // Each save waits for all of them to have started
                allStarted.countDown();
                try {
                    if (!allStarted.await(30, TimeUnit.SECONDS)) return;
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
                saved.incrementAndGet();
            }

            @Override
            public boolean supportsParallelSaving() {
                return true;
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> loader.saveChunks(Collections.nCopies(70_000, chunk)));
        assertEquals(70_000, saved.get());
    }
}
