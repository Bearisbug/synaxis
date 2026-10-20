package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@EnvTest
public class PlayerChunkLoadFailureIntegrationTest {

    private static final class BrokenChunkLoader implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }

    @Test
    public void failingChunkLoaderDoesNotHangSetInstance(Env env) {
        // The failing loader legitimately reports to the exception handler, which would otherwise fail the test
        env.process().exception().setExceptionHandler(_ -> {
        });
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var broken = env.process().instance().createInstanceContainer(new BrokenChunkLoader());

        var thrown = new CompletableFuture<Throwable>();
        var task = CompletableFuture.runAsync(() -> {
            try {
                player.setInstance(broken, new Pos(0, 42, 0)).join();
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
        });

        assertDoesNotThrow(() -> task.get(5, TimeUnit.SECONDS),
                "setInstance never completed after a chunk load failure");
        var error = assertDoesNotThrow(() -> thrown.get(5, TimeUnit.SECONDS));
        assertInstanceOf(CompletionException.class, error, "the chunk load failure must reach the caller");
        assertEquals(0, broken.chunkTicketCount(0, 0), "the failed change left the view tickets of its ring behind");
    }
}
