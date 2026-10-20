package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Point;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public final class ChunkUtils {

    private ChunkUtils() {
    }

    /**
     * Executes {@link Instance#loadOptionalChunk(int, int)} for the array of chunks {@code chunks}.
     * <p>
     * Be aware that {@link Instance#loadOptionalChunk(int, int)} can give a null chunk
     * if {@link Instance#hasEnabledAutoChunkLoad()} returns false and the chunk is not already loaded.
     *
     * @param instance the instance to load the chunks from
     * @param chunks   the chunks to loaded, long value from {@link CoordConversion#chunkIndex(int, int)}
     * @return a {@link CompletableFuture} completed once all chunks have been processed,
     * or completed exceptionally if any chunk fails to load
     */
    public static CompletableFuture<Void> optionalLoadAll(Instance instance, long [] chunks) {
        CompletableFuture<?>[] futures = new CompletableFuture<?>[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            final long visibleChunk = chunks[i];
            futures[i] = instance.loadOptionalChunk(
                    CoordConversion.chunkIndexGetX(visibleChunk), CoordConversion.chunkIndexGetZ(visibleChunk));
        }
        return CompletableFuture.allOf(futures);
    }

    public static boolean isLoaded(@Nullable Chunk chunk) {
        return chunk != null && chunk.isLoaded();
    }

    public static boolean isLoaded(Instance instance, Point point) {
        final Chunk chunk = instance.getChunk(point.chunkX(), point.chunkZ());
        return isLoaded(chunk);
    }

    public static @Nullable Chunk retrieve(Instance instance, @Nullable Chunk originChunk, Point position) {
        final int chunkX = position.chunkX();
        final int chunkZ = position.chunkZ();
        final boolean sameChunk = originChunk != null &&
                originChunk.getChunkX() == chunkX && originChunk.getChunkZ() == chunkZ;
        return sameChunk ? originChunk : instance.getChunk(chunkX, chunkZ);
    }
}
