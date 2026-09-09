package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    }
}
