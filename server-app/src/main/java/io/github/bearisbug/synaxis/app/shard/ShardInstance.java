package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * The map instance of a shard ({@code docs/DESIGN-M3.md} {@code API-M3-006} 「view_bounds 外的区块请求」,
 * M3-S {@code REQ-M3-029}): an {@link InstanceContainer} that counts the requests for a chunk outside
 * {@code view_bounds}, where the loader has nothing and the engine builds an air chunk. The count goes into
 * {@code EVT:chunk_stream_stats.void_chunks}. The first request logs a WARN with the stack of the thread that
 * made it, which is the requester's: {@code loadChunk} and {@code loadOptionalChunk} call
 * {@link #retrieveChunk} on it, while the air chunk is then built on a virtual thread of the load. The later
 * ones are rate limited and without the stack, all through one {@link LogBudget}: a client that walks out of
 * a bounds that is not square picks how often this runs. Two requests of one chunk at once count twice.
 */
public final class ShardInstance extends InstanceContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShardInstance.class);

    private final SynxManifest.Bounds viewBounds;
    private final LongAdder voidChunks = new LongAdder();
    private final AtomicBoolean stackLogged = new AtomicBoolean();
    private final LogBudget voidRequests = new LogBudget(LOGGER, "void chunk requests");

    /**
     * Built as {@code InstanceManager#createInstanceContainer} builds an instance; the caller registers it.
     *
     * @param viewBounds the chunks the loader has records of, {@code view_bounds}
     */
    ShardInstance(ServerProcess process, RegistryKey<DimensionType> dimensionType, ChunkLoader loader,
                  SynxManifest.Bounds viewBounds) {
        super(process, UUID.randomUUID(), dimensionType, loader, dimensionType.key());
        this.viewBounds = viewBounds;
    }

    @Override
    protected CompletableFuture<Chunk> retrieveChunk(int chunkX, int chunkZ) {
        if (chunkX < viewBounds.minX() || chunkX > viewBounds.maxX() || chunkZ < viewBounds.minZ()
                || chunkZ > viewBounds.maxZ()) {
            voidChunks.increment();
            if (stackLogged.compareAndSet(false, true)) {
                voidRequests.warn(new Throwable("requested here"), "Void chunk requested at {} {}: outside the view bounds {}",
                        chunkX, chunkZ, viewBounds);
            } else {
                voidRequests.warn("Void chunk requested at {} {}: outside the view bounds {}", chunkX, chunkZ, viewBounds);
            }
        }
        return super.retrieveChunk(chunkX, chunkZ);
    }

    /** The requests for a chunk outside {@code view_bounds} not loaded yet, since the instance was built. */
    public long voidChunks() {
        return voidChunks.sum();
    }
}
