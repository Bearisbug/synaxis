package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;

/**
 * Loads the chunks of a map release ({@code docs/DESIGN-M3.md} API-M3-005): one per shard process,
 * owner of the section cache. It reads the region files that {@code view_bounds} (the region, {@code bounds},
 * grown by {@value #VIEW_MARGIN} chunks) shares with the release through the mappings the start-up check
 * made and checked ({@link VerifiedBuild}), read only, for the life of the process.
 * <table>
 *     <caption>What {@link #loadChunk} returns</caption>
 *     <tr><th>Chunk</th><th>Looks up</th><th>Found</th><th>Returns</th></tr>
 *     <tr><td>in {@code bounds}</td><td>the overlay store first</td><td>a hit</td><td>handle mode on the
 *     overlay record, {@code generation} going on from it</td></tr>
 *     <tr><td>in {@code bounds}</td><td>then the region file</td><td>a record</td><td>handle mode,
 *     {@code generation} 0</td></tr>
 *     <tr><td>in {@code bounds}</td><td>the region file</td><td>none, or a broken record</td><td>failed
 *     mode</td></tr>
 *     <tr><td>in {@code view_bounds} only</td><td>the region file alone</td><td>a record</td><td>read
 *     only handle mode</td></tr>
 *     <tr><td>in {@code view_bounds} only</td><td>the region file alone</td><td>a broken record</td><td>read
 *     only failed mode</td></tr>
 *     <tr><td>in {@code view_bounds} only</td><td>the region file alone</td><td>none</td><td>null</td></tr>
 *     <tr><td>outside {@code view_bounds}</td><td>nothing</td><td></td><td>null</td></tr>
 * </table>
 * On null the instance builds an air chunk of its own. Loading is thread safe: the mappings are read
 * only, {@link SynxRegion#slot} keeps no state and {@link OverlayIndex#find} is thread safe, so the
 * engine loads on its virtual threads in parallel.
 */
public final class SynxChunkLoader implements ChunkLoader {
    /** How far {@code view_bounds} reaches past {@code bounds}: the view distance cap 16, plus the one ring more the engine sends. */
    public static final int VIEW_MARGIN = 17;

    private static final Logger LOGGER = LoggerFactory.getLogger(SynxChunkLoader.class);

    private final SynxManifest manifest;
    private final SynxManifest.Bounds bounds;
    private final SynxManifest.Bounds viewBounds;
    private final SynxOptions options;
    private final OverlayIndex overlay;
    private final OverlayTracker tracker;
    private final VerifiedBuild verified;
    private final SectionCache cache;
    private final Set<String> warnedEntityTypes = ConcurrentHashMap.newKeySet();

    private final LongAdder handleSent = new LongAdder();
    private final LongAdder overlaySent = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder loaded = new LongAdder();
    private final LongAdder unloaded = new LongAdder();
    // Set once, by startRelight, before the instance ticks
    private volatile @Nullable SynxRelight relight;

    /**
     * Loads from the region files the start-up check of API-M3-002 mapped and checked
     * ({@link SynxBuildVerifier#open}), for the {@code view_bounds} of {@code bounds}; nothing is
     * opened by its path or checked again here.
     *
     * @param verified the release, its regions mapped over the {@code view_bounds} of {@code bounds}
     * @param bounds   the region this shard serves: changes and the overlay store apply inside it only
     * @param overlay  where chunks of the region load their overlay content from
     * @param tracker  what the chunks tell about their overlay life
     */
    public SynxChunkLoader(VerifiedBuild verified, SynxManifest.Bounds bounds, SynxOptions options,
                           OverlayIndex overlay, OverlayTracker tracker) {
        this.verified = verified;
        this.manifest = verified.manifest();
        this.bounds = bounds;
        this.viewBounds = bounds.grow(VIEW_MARGIN);
        this.options = options;
        this.overlay = overlay;
        this.tracker = tracker;
        this.cache = new SectionCache(options.sectionCacheBytes());
    }

    @Override
    public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        if (!viewBounds.contains(chunkX, chunkZ)) return null;
        final boolean inRegion = bounds.contains(chunkX, chunkZ);
        if (inRegion) {
            final OverlayHit hit = overlay.find(chunkX, chunkZ);
            if (hit != null) {
                final String file = "the overlay segment " + OverlaySegment.fileName(hit.segment());
                try {
                    return handle(instance, chunkX, chunkZ, true, file, hit.record(), new SectionCache.Key(hit.segment(), hit.offset()),
                            hit.generation());
                } catch (InternalError _) {
                    return faulted(instance, chunkX, chunkZ, true, file);
                }
            }
        }
        final int regionX = SynxFormat.region(chunkX), regionZ = SynxFormat.region(chunkZ);
        final SynxRegion region = verified.region(regionX, regionZ);
        final String file = "the region file " + SynxFormat.regionFileName(regionX, regionZ);
        try {
            final SynxSlot slot = region != null ? region.slot(chunkX, chunkZ) : new SynxSlot.Absent();
            return switch (slot) {
                case SynxSlot.Present present -> handle(instance, chunkX, chunkZ, inRegion, file, present,
                        SectionCache.Key.regionFile(chunkX, chunkZ), 0);
                case SynxSlot.Failed broken -> failed(instance, chunkX, chunkZ, inRegion,
                        file + ": " + broken.fault() + " " + broken.detail());
                case SynxSlot.Absent _ -> inRegion
                        ? failed(instance, chunkX, chunkZ, true, region != null ? "no record" : "no region file")
                        : null;
            };
        } catch (InternalError _) {
            return faulted(instance, chunkX, chunkZ, inRegion, file);
        }
    }

    /**
     * A mapping that faulted while the chunk loaded from it ({@code API-M3-005} 映射读取出错): the file was
     * cut short under the shard, which the start-up check cannot stop. The chunk loads as failed, the
     * process goes on.
     */
    private SynxChunk faulted(Instance instance, int chunkX, int chunkZ, boolean inRegion, String file) {
        return failed(instance, chunkX, chunkZ, inRegion, file + " faulted on read");
    }

    /** A handle chunk on {@code record}, read from {@code file}, or a failed one when the record does not read. */
    private SynxChunk handle(Instance instance, int chunkX, int chunkZ, boolean inRegion, String file,
                             SynxSlot.Present record, SectionCache.Key key, long generation) {
        try {
            // The two VarInts of the frame, before anything reads the packet (M3-S, API-M3-001 长度上限)
            SynxChunkPacket.checkFrame(record.packet());
        } catch (IllegalArgumentException e) {
            return failed(instance, chunkX, chunkZ, inRegion, file + ", segment ①: " + e.getMessage());
        }
        final SynxChunk.Source source;
        try {
            source = source(record, key, instance.process().block());
        } catch (IllegalArgumentException e) {
            return failed(instance, chunkX, chunkZ, inRegion, file + ", segment ③: " + e.getMessage());
        }
        final SynxChunk chunk;
        try {
            // A handler that ticks takes its block from segment ① here, the first read of its body
            chunk = SynxChunk.handle(instance, chunkX, chunkZ, this, inRegion, source, generation);
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            return failed(instance, chunkX, chunkZ, inRegion, file + ", segment ①: " + e.getMessage());
        }
        loaded.increment();
        return chunk;
    }

    private SynxChunk failed(Instance instance, int chunkX, int chunkZ, boolean inRegion, String detail) {
        LOGGER.error("Chunk {},{} of {} does not read, it loads as read only air: {}", chunkX, chunkZ,
                manifest.buildId(), detail);
        failed.increment();
        loaded.increment();
        return SynxChunk.failed(instance, chunkX, chunkZ, this, inRegion);
    }

    /**
     * What a chunk reads and sends from {@code record}: segment ③ decoded, the handlers of its block
     * entities taken from {@code blocks}, segment ① wrapped for the socket as it is.
     *
     * @throws IllegalArgumentException if segment ③ does not decode, or has a block entity outside the world
     */
    SynxChunk.Source source(SynxSlot.Present record, SectionCache.Key key, BlockManager blocks) {
        final SynxEntities.Tables tables = SynxEntities.decode(record.entityTables());
        final Int2ObjectMap<SynxChunk.BlockEntity> blockEntities = tables.blockEntities().isEmpty()
                ? Int2ObjectMaps.emptyMap() : new Int2ObjectOpenHashMap<>(tables.blockEntities().size());
        final int minSection = manifest.world().minY() >> 4, maxSection = minSection + manifest.world().height() / 16;
        for (SynxBlockEntity entity : tables.blockEntities()) {
            // The chunk indexes its sections by it: one outside them would throw on every read and change
            if (entity.sectionY() < minSection || entity.sectionY() >= maxSection) {
                throw new IllegalArgumentException("a block entity in section " + entity.sectionY()
                        + ", the world is sections " + minSection + " to " + (maxSection - 1));
            }
            blockEntities.put(entity.chunkBlockIndex(), SynxChunk.BlockEntity.of(entity, blocks));
        }
        final long length = record.packet().byteSize();
        final BufferedPacket packet = new BufferedPacket(NetworkBuffer.wrap(record.packet(), 0, length), 0, length, false);
        return new SynxChunk.Source(record, key, packet, blockEntities, tables.staticEntities());
    }

    /** The section palettes of a source, from the cache. */
    SectionCache.Entry sections(SynxChunk.Source source, Instance instance) {
        return cache.get(source.key(), source.slot().packet(), instance.registries(), manifest.world().height() / 16);
    }

    void spawnStatics(Instance instance, List<CompoundBinaryTag> entities) {
        SynxStaticEntities.spawn(instance, entities, warnedEntityTypes);
    }

    /**
     * The instance of a chunk must have the world of the release, or segment ① does not fit its sections.
     *
     * @throws IllegalStateException if the sections {@code [minSection, maxSection)} are another world
     */
    void requireWorld(int minSection, int maxSection) {
        final SynxManifest.World world = manifest.world();
        if (minSection * 16 != world.minY() || (maxSection - minSection) * 16 != world.height()) {
            throw new IllegalStateException("an instance of y " + minSection * 16 + " height " + (maxSection - minSection) * 16
                    + ", the release " + manifest.buildId() + " is y " + world.minY() + " height " + world.height());
        }
    }

    /** Handle chunks save nothing; overlay chunks only reach the disk through the overlay store's write phase. */
    @Override
    public void saveChunk(Chunk chunk) {
    }

    @Override
    public boolean supportsParallelLoading() {
        return true;
    }

    /** Counts the unload and tells the overlay store about a chunk of the region; the caller holds the instance monitor. */
    @Override
    public void unloadChunk(Chunk chunk) {
        unloaded.increment();
        if (chunk instanceof SynxChunk synx && bounds.contains(synx.chunkX(), synx.chunkZ())
                && synx.mode() != SynxChunk.Mode.FAILED) {
            tracker.unloaded(synx);
        }
    }

    /**
     * Starts the runtime light of {@code instance}, the instance of this loader
     * ({@code docs/DESIGN-M3.md} {@code API-M3-005} 运行时光照): from now on a change of block state
     * queues a recomputation of the 3×3 around its chunk, on a pool of
     * {@link SynxOptions#relightThreads()} platform threads of its own. Without it no change queues one,
     * as in M3-C.
     * <p>
     * The caller runs {@link SynxRelight#installPhase()} once at the end of every tick on the tick
     * scheduling thread, and registers {@link SynxRelight#drain} as a shutdown step before the final
     * flush of the overlay store ({@code ShardWorld#start} does both). Until a tick installs them no result
     * goes in: {@code relight_pending} stays above 0 and the chunks around each change stay loaded.
     *
     * @throws IllegalStateException on a second call
     */
    public SynxRelight startRelight(InstanceContainer instance) {
        return startRelight(instance, null);
    }

    /**
     * {@link #startRelight(InstanceContainer)} with the snapshot and the computation of each
     * recomputation handed to {@code computeExecutor} instead of a pool, unless it is null: the tests stop
     * a recomputation after its snapshot that way.
     *
     * @throws IllegalStateException on a second call
     */
    public synchronized SynxRelight startRelight(InstanceContainer instance, @Nullable Executor computeExecutor) {
        if (relight != null) throw new IllegalStateException("the runtime light of this loader has started already");
        final SynxRelight started = new SynxRelight(this, instance, computeExecutor);
        this.relight = started;
        started.listen();
        return started;
    }

    /** The runtime light, null before {@link #startRelight}. */
    @Nullable SynxRelight relight() {
        return relight;
    }

    /** Counters since construction, and the current size of the section cache and the mappings. */
    public SynxStreamStats stats() {
        return new SynxStreamStats(handleSent.sum(), overlaySent.sum(), bytesSent.sum(), failed.sum(), loaded.sum(),
                unloaded.sum(), cache.bytes(), verified.regionCount(), verified.bytesMapped());
    }

    SynxOptions options() {
        return options;
    }

    /** The world of the release. */
    SynxManifest.World world() {
        return manifest.world();
    }

    /** Whether a chunk position is in {@code view_bounds}, where this loader has anything to load. */
    boolean inView(int chunkX, int chunkZ) {
        return viewBounds.contains(chunkX, chunkZ);
    }

    OverlayTracker tracker() {
        return tracker;
    }

    void handleSent(long bytes) {
        handleSent.increment();
        bytesSent.add(bytes);
    }

    void overlaySent() {
        overlaySent.increment();
    }
}
