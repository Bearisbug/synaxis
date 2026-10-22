package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.Section;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * The map instance of a shard ({@code docs/DESIGN-M3.md} {@code API-M3-006} 「view_bounds 外的区块请求」,
 * {@code REQ-M3-029}): an {@link InstanceContainer} that counts the requests for a chunk outside
 * {@code view_bounds}, where the loader has nothing and the engine builds an air chunk. The count goes into
 * {@code EVT:chunk_stream_stats.void_chunks}. The first request logs a WARN with the stack of the thread that
 * made it, which is the requester's: {@code loadChunk} and {@code loadOptionalChunk} call
 * {@link #retrieveChunk} on it, while the air chunk is then built on a virtual thread of the load. That line
 * has a {@link LogBudget} of its own, which nothing else claims, so a request at the same time cannot take
 * its turn. The later ones are rate limited and without the stack, all through one more {@link LogBudget}: a
 * client that walks out of a bounds that is not square picks how often this runs. Two requests of one chunk
 * at once count twice.
 * <p>
 * The air chunks the engine builds where the loader returns null, outside {@code view_bounds} and at the
 * edge of the map inside it, have full sky light ({@code API-M3-005} 范围与加载结果).
 */
public final class ShardInstance extends InstanceContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShardInstance.class);
    private static final String VOID_REQUEST = "Void chunk requested at {} {}: outside the view bounds {}";
    /** Sky light 15 everywhere in a section, as the failed placeholder of {@code SynxChunk} has it. */
    private static final byte[] FULL_SKY_LIGHT = new byte[2048];

    static {
        Arrays.fill(FULL_SKY_LIGHT, (byte) 0xFF);
    }

    private final SynxManifest.Bounds viewBounds;
    private final LongAdder voidChunks = new LongAdder();
    private final AtomicBoolean stackLogged = new AtomicBoolean();
    private final LogBudget firstVoidRequest = new LogBudget(LOGGER, "void chunk requests");
    /** The later requests; package private so a test can take its turn as a request at the same time would. */
    final LogBudget voidRequests = new LogBudget(LOGGER, "void chunk requests");

    /**
     * Built as {@code InstanceManager#createInstanceContainer} builds an instance; the caller registers it.
     *
     * @param viewBounds the chunks the loader has records of, {@code view_bounds}
     */
    ShardInstance(ServerProcess process, RegistryKey<DimensionType> dimensionType, ChunkLoader loader,
                  SynxManifest.Bounds viewBounds) {
        super(process, UUID.randomUUID(), dimensionType, loader, dimensionType.key());
        this.viewBounds = viewBounds;
        setChunkSupplier(ShardInstance::skyLitAir);
    }

    /**
     * An air chunk with full sky light in every section: without it the client would draw it, and the
     * faces of its neighbours towards it, dark. It is not a {@code SynxChunk}, so the runtime light still
     * takes it for a missing chunk, as the baker does.
     */
    private static Chunk skyLitAir(Instance instance, int chunkX, int chunkZ) {
        final DynamicChunk chunk = new DynamicChunk(instance, chunkX, chunkZ);
        for (Section section : chunk.getSections()) section.setSkyLight(FULL_SKY_LIGHT);
        return chunk;
    }

    @Override
    protected CompletableFuture<Chunk> retrieveChunk(int chunkX, int chunkZ) {
        if (!viewBounds.contains(chunkX, chunkZ)) {
            voidChunks.increment();
            if (stackLogged.compareAndSet(false, true)) {
                firstVoidRequest.warn(new Throwable("requested here"), VOID_REQUEST, chunkX, chunkZ, viewBounds);
            } else {
                voidRequests.warn(VOID_REQUEST, chunkX, chunkZ, viewBounds);
            }
        }
        return super.retrieveChunk(chunkX, chunkZ);
    }

    /** The requests for a chunk outside {@code view_bounds} not loaded yet, since the instance was built. */
    public long voidChunks() {
        return voidChunks.sum();
    }
}
