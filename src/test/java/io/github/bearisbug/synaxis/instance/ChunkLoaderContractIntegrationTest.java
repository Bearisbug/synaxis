package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class ChunkLoaderContractIntegrationTest {

    // A chunk the loader built for other coordinates fails the load instead of replacing the chunk it claims to be
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void aChunkOfOtherCoordinatesFailsTheLoad(boolean parallel, Env env) throws Exception {
        CompletableFuture<Throwable> handled = new CompletableFuture<>();
        env.process().exception().setExceptionHandler(handled::complete);
        var instance = env.createEmptyInstance(new ChunkLoader() {
            @Override
            public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
                return chunkX == 1 && chunkZ == 2 ? new DynamicChunk(instance, 2, 1) : null;
            }

            @Override
            public void saveChunk(Chunk chunk) {
            }

            @Override
            public boolean supportsParallelLoading() {
                return parallel;
            }
        });
        var loaded = instance.loadChunk(2, 1).join();

        var thrown = assertThrows(CompletionException.class, () -> instance.loadChunk(1, 2).join());
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertSame(thrown.getCause(), handled.get(5, TimeUnit.SECONDS), "the exception handler gets the failure");
        assertSame(loaded, instance.getChunk(2, 1), "the chunk at 2,1 stays");
        assertNull(instance.getChunk(1, 2));
    }
}
