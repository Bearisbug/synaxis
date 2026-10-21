package io.github.bearisbug.synaxis.instance;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.Executors;

/**
 * Interface implemented to change the way chunks are loaded/saved.
 * <p>
 * {@link InstanceContainer} defaults to {@link #noop()}.
 */
public interface ChunkLoader {

    /**
     * Returns the no op chunk loader
     * @return the no op loader.
     */
    @Contract(pure = true)
    static ChunkLoader noop() {
        return NoopChunkLoaderImpl.INSTANCE;
    }

    /**
     * Loads instance data from the loader.
     *
     * @param instance the instance to retrieve the data from
     */
    default void loadInstance(Instance instance) {
    }

    /**
     * Loads a {@link Chunk}, all blocks should be set since the {@link io.github.bearisbug.synaxis.instance.generator.Generator} is not applied.
     * <p>
     * Implementations must not initiate another chunk load on the same {@link Instance} from this method. Doing so
     * re-enters the instance's loading registry and may fail with an {@link IllegalStateException}. If loading a
     * chunk requires data associated with neighboring chunks, read that data directly from the backing storage
     * instead of calling {@link Instance#loadChunk(int, int)} or {@link Instance#loadOptionalChunk(int, int)}.
     * <p>
     * A loader must never wait for the same chunk it is currently loading. Requests for an in-progress chunk share
     * the existing future, so waiting for that future from this method would wait for the current invocation.
     * <p>
     * The chunk returned is a new object that belongs to {@code instance} and sits at ({@code chunkX},
     * {@code chunkZ}). A chunk of another instance or of other coordinates fails the load with an
     * {@link IllegalStateException}.
     *
     * @param instance the {@link Instance} where the {@link Chunk} belong
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return the chunk, or null if not present
     */
    @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ);

    default void saveInstance(Instance instance) {
    }

    /**
     * Saves a {@link Chunk}.
     *
     * @param chunk the {@link Chunk} to save
     */
    void saveChunk(Chunk chunk);

    /**
     * Saves multiple chunks.
     * <p>
     * When {@link #supportsParallelSaving()} is true, the default implementation calls {@link #saveChunk(Chunk)}
     * for all of them at once, each on a virtual thread of its own, and returns once every call returned: a save
     * that throws goes to the exception handler, the other chunks are saved anyway. Otherwise it calls them one
     * after the other and stops at the first exception. The engine calls this without holding any chunk lock: an
     * implementation that needs a consistent view of a chunk takes its {@link Chunk#lockReadLock() read lock}.
     * In parallel, interrupting the calling thread interrupts the saves still running.
     *
     * @param chunks the chunks to save
     */
    default void saveChunks(Collection<Chunk> chunks) {
        if (supportsParallelSaving()) {
            // Closing the executor waits for every save
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Chunk chunk : chunks) {
                    executor.execute(() -> {
                        try {
                            saveChunk(chunk);
                        } catch (Throwable e) {
                            chunk.getInstance().process().exception().handleException(e);
                        }
                    });
                }
            }
        } else {
            for (Chunk chunk : chunks) {
                saveChunk(chunk);
            }
        }
    }

    /**
     * Supports for instance/chunk saving in virtual threads.
     *
     * @return true if the chunk loader supports parallel saving
     */
    default boolean supportsParallelSaving() {
        return false;
    }

    /**
     * Supports for instance/chunk loading in virtual threads.
     * <p>
     * When true, chunks may load in parallel on virtual threads. When false,
     * {@link #loadChunk(Instance, int, int)} runs synchronously on the thread
     * requesting the load. The re-entrant loading restrictions documented on
     * {@link #loadChunk(Instance, int, int)} apply in either case.
     *
     * @return true if the chunk loader supports parallel loading
     */
    default boolean supportsParallelLoading() {
        return false;
    }

    /**
     * Called when a chunk is unloaded, so that this chunk loader can unload any resource it is holding.
     * Note: the engine currently has no way to determine whether the chunk comes from this loader, so you may get
     * unload requests for chunks not created by the loader.
     * <p>
     * {@link InstanceContainer#unloadChunk(Chunk)} calls it once the chunk has left the instance and is marked
     * unloaded. The caller holds the instance monitor, which every block change through the instance
     * ({@code setBlock}, {@code placeBlock}, {@code breakBlock}) takes too: as long as this runs, they wait
     * on every thread. The caller holds no chunk lock.
     * <p>
     * It is called on one of two threads. The automatic unload calls it on the tick scheduler thread, at most
     * 256 times per instance and tick, and the next tick waits for all of them to return; an explicit
     * {@link Instance#unloadChunk(Chunk)} or {@link InstanceManager#unregisterInstance(Instance)} calls it on
     * the thread of its caller. Do no blocking IO here: hand the writing to another thread, and override
     * {@link Chunk#readyToUnload()} to hold off the automatic unload of a chunk that has to be written first.
     *
     * @param chunk the chunk to unload
     */
    default void unloadChunk(Chunk chunk) {
    }
}
