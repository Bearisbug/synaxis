package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.Section;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.instance.heightmap.MotionBlockingHeightmap;
import io.github.bearisbug.synaxis.instance.heightmap.WorldSurfaceHeightmap;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.biome.Biome;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.ValueLayout;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.github.bearisbug.synaxis.synx.SynxBlockEntity.withoutRecordKeys;

/**
 * A chunk of a {@code .synx} map ({@code docs/DESIGN-M3.md} API-M3-005, {@code ADR-M3-016}). Handle,
 * overlay and failed mode are states of the one object, which switches in place under its write-lock.
 * <ul>
 *     <li>Handle: the sections of {@link DynamicChunk} stay empty and are neither read nor sent. Blocks
 *     come from the section cache of the loader, block entities from segment ③, and the full data
 *     packet is segment ① as it is, a {@link BufferedPacket} over the record.</li>
 *     <li>Overlay: the first change, or new light of a recomputation (§11.1 为光照物化), fills the sections
 *     from segment ① and segment ③, light included; the overlay store writes it out, and its eviction
 *     demotes the chunk back to handle mode on the record written.</li>
 *     <li>Failed: a record that does not read, a mapping that faults, or no record inside the region,
 *     loads as air with full sky light, read only.</li>
 * </ul>
 * A chunk outside the region ({@code bounds}), in a world that is not writable or with the overlay
 * store full is read only as well: changes are ignored silently, as the engine ignores them on any read
 * only chunk. A world state flag still changes a chunk of the region in the last two cases
 * ({@link #asWorldFlag}).
 */
public final class SynxChunk extends DynamicChunk implements OverlayChunk {
    enum Mode {HANDLE, OVERLAY, FAILED}

    /**
     * What a chunk reads and sends in handle mode, and what an overlay chunk takes segment ② and its
     * static entities from.
     *
     * @param blockEntities segment ③'s block entities by {@link CoordConversion#chunkBlockIndex}
     */
    record Source(SynxSlot.Present slot, SectionCache.Key key, BufferedPacket packet,
                  Int2ObjectMap<BlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities) {
    }

    /**
     * A block entity of segment ③ as the chunk hands it out: its NBT without the record keys (the form
     * the baker sent the client its tags from) and the handler of its namespace, taken from the
     * {@link BlockManager} once for the record, so that reads, the tick and the overlay entries share
     * one handler even where the registration makes a new one on every call. A namespace the shard did
     * not register gets the engine's dummy, which keeps the namespace and shows the client nothing.
     */
    record BlockEntity(CompoundBinaryTag nbt, @Nullable BlockHandler handler) {
        static BlockEntity of(SynxBlockEntity entity, BlockManager blocks) {
            return new BlockEntity(withoutRecordKeys(entity.nbt()),
                    entity.handler().isEmpty() ? null : blocks.getHandlerOrDummy(entity.handler()));
        }

        Block block(int state) {
            final Block block = Objects.requireNonNullElse(Block.fromStateId(state), Block.AIR).withNbt(nbt);
            return handler != null ? block.withHandler(handler) : block;
        }
    }

    /**
     * The overlay content one {@code generation} sends and records ({@link #snapshot()}).
     *
     * @param invalidations the {@link #invalidate()} calls it was taken after
     */
    private record Snapshot(long generation, long invalidations, CachedPacket packet) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SynxChunk.class);
    private static final long LOCK_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final byte[] NO_LIGHT = new byte[0];
    private static final byte[] FULL_LIGHT = new byte[2048];
    /** Bound while this thread applies a world state flag ({@link #asWorldFlag}). */
    private static final ScopedValue<Boolean> WORLD_FLAG = ScopedValue.newInstance();
    /** The states the client and the baker count as air besides air itself, state 0. */
    private static final int[] OTHER_AIR = IntStream.range(1, Block.statesCount())
            .filter(state -> Objects.requireNonNull(Block.fromStateId(state)).air()).toArray();

    static {
        Arrays.fill(FULL_LIGHT, (byte) 0xFF);
    }

    private final SynxChunkLoader loader;
    private final boolean inRegion;
    // Written under the write-lock, read without it by the senders, the overlay store and the unload
    private volatile Mode mode;
    private volatile @Nullable Source source;
    private final AtomicLong generation;
    private volatile boolean dirty;
    private final AtomicLong invalidations = new AtomicLong();
    // Replaced under the write-lock; soft like the engine's own chunk packet cache, taken again once dropped
    private volatile @Nullable SoftReference<Snapshot> snapshot;
    // Weak: the section cache alone decides how long decoded palettes stay (SectionCache)
    private volatile @Nullable WeakReference<SectionCache.Entry> decoded;
    // The runtime light (API-M3-005 运行时光照): changes of block state, written under the write-lock, read by
    // the install without it; and who holds this chunk since which tick, who may hold stale light of it
    private final AtomicLong blockChanges = new AtomicLong();
    private final Map<Player, Long> delivered = new ConcurrentHashMap<>();
    private final Map<Player, Boolean> lightStale = new ConcurrentHashMap<>();

    private SynxChunk(Instance instance, int chunkX, int chunkZ, SynxChunkLoader loader, boolean inRegion,
                      Mode mode, @Nullable Source source, long generation) {
        super(instance, chunkX, chunkZ);
        loader.requireWorld(minSection, maxSection);
        this.loader = loader;
        this.inRegion = inRegion;
        this.mode = mode;
        this.source = source;
        this.generation = new AtomicLong(generation);
    }

    /** A handle chunk on {@code source}, {@code generation} going on from the source's. */
    static SynxChunk handle(Instance instance, int chunkX, int chunkZ, SynxChunkLoader loader, boolean inRegion,
                            Source source, long generation) {
        final SynxChunk chunk = new SynxChunk(instance, chunkX, chunkZ, loader, inRegion, Mode.HANDLE, source, generation);
        chunk.registerTickables(source);
        return chunk;
    }

    /**
     * Air in place of a record that does not read. Without light the client would draw it, and the
     * faces of its neighbours towards it, black: every section gets full sky light.
     */
    static SynxChunk failed(Instance instance, int chunkX, int chunkZ, SynxChunkLoader loader, boolean inRegion) {
        final SynxChunk chunk = new SynxChunk(instance, chunkX, chunkZ, loader, inRegion, Mode.FAILED, null, 0);
        for (Section section : chunk.sections) section.setSkyLight(FULL_LIGHT);
        return chunk;
    }

    Mode mode() {
        return mode;
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public long generation() {
        return generation.get();
    }

    @Override
    public boolean dirty() {
        return dirty;
    }

    @Override
    public boolean relightPending() {
        final SynxRelight relight = loader.relight();
        return relight != null && relight.pending(chunkX, chunkZ) > 0;
    }

    /**
     * Runs {@code action} as the application of a world state flag ({@code docs/DESIGN-M3.md}
     * {@code API-M3-005}, {@code API-M3-013}): on this thread and for as long as it runs,
     * {@link #isReadOnly()} leaves {@code synaxis.world-writable} and a full overlay store
     * ({@link OverlayTracker#full()}) out, so that a flag changes a world nothing else may change.
     * Outside the region and in failed mode a chunk stays read only.
     *
     * @param action what changes the blocks
     */
    public static void asWorldFlag(Runnable action) {
        ScopedValue.where(WORLD_FLAG, Boolean.TRUE).run(action);
    }

    /**
     * Whether a cell that holds {@code block} holds {@code target} as far as an overlay record and
     * segment ③ keep a block: the same state, handlers of the same namespace or none, and the same NBT
     * once the record keys are left out, no NBT counting as an empty one. Loaded from a record, a block
     * entity set without NBT comes back with an empty one, without the record keys, and with the handler
     * the {@link BlockManager} gives for its namespace ({@link BlockEntity}), which {@code Block#equals}
     * tells from the block that was set.
     *
     * @param block  what the cell holds
     * @param target what it is to hold
     * @return true when setting {@code target} would change nothing a record keeps
     */
    public static boolean sameBlock(Block block, Block target) {
        if (block.stateId() != target.stateId()) return false;
        final BlockHandler handler = block.handler();
        final BlockHandler targetHandler = target.handler();
        if (handler == null ? targetHandler != null
                : targetHandler == null || !handler.getKey().equals(targetHandler.getKey())) return false;
        return withoutRecordKeys(block.nbtOrEmpty()).equals(withoutRecordKeys(target.nbtOrEmpty()));
    }

    /**
     * Outside the region, in failed mode, or with {@code synaxis.world-writable=false} or the overlay store
     * full ({@link OverlayTracker#full()}, M3-S) unless this thread applies a world state flag
     * ({@link #asWorldFlag}). The engine checks this in
     * {@code InstanceContainer} only; {@link #setBlock} and {@link #setBiome} check it again for the
     * callers that come around it.
     */
    @Override
    public boolean isReadOnly() {
        return !inRegion || mode == Mode.FAILED
                || ((!loader.options().worldWritable() || loader.tracker().full()) && !WORLD_FLAG.isBound())
                || super.isReadOnly();
    }

    @Override
    public void setBlock(int x, int y, int z, Block block, @Nullable BlockHandler.Placement placement,
                         @Nullable BlockHandler.Destroy destroy) {
        if (isReadOnly()) return;
        if (y < minSection * CHUNK_SECTION_SIZE || y >= maxSection * CHUNK_SECTION_SIZE) {
            super.setBlock(x, y, z, block, placement, destroy); // warns and changes nothing
            return;
        }
        final SynxRelight relight = loader.relight();
        final int before = relight != null ? getBlock(x, y, z, Condition.TYPE).stateId() : block.stateId();
        if (mode == Mode.HANDLE) materialize();
        try {
            super.setBlock(x, y, z, block, placement, destroy);
        } finally {
            changed(); // a handler that throws in onPlace or onDestroy does so after the palette has the change
            if (before != block.stateId() && relight != null && relight.instance() == instance) {
                blockChanges.incrementAndGet();
                relight.changed(this);
            }
        }
    }

    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        if (isReadOnly()) return;
        if (mode == Mode.HANDLE) materialize();
        try {
            super.setBiome(x, y, z, biome);
        } finally {
            changed(); // an unregistered biome throws, but the chunk is materialized: dirty, written like any other
        }
    }

    private void changed() {
        generation.incrementAndGet();
        this.dirty = true;
        loader.tracker().modified(this);
    }

    @Override
    public @Nullable Block getBlock(int x, int y, int z, Condition condition) {
        if (mode != Mode.HANDLE) return super.getBlock(x, y, z, condition);
        final Source source = Objects.requireNonNull(this.source);
        if (y < minSection * CHUNK_SECTION_SIZE || y >= maxSection * CHUNK_SECTION_SIZE) return Block.AIR;
        if (condition != Condition.TYPE) {
            final BlockEntity entity = source.blockEntities().get(CoordConversion.chunkBlockIndex(x, y, z));
            if (entity != null) return entity.block(state(source, x, y, z));
            if (condition == Condition.CACHED) return null;
        }
        return Objects.requireNonNullElse(Block.fromStateId(state(source, x, y, z)), Block.AIR);
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        if (mode != Mode.HANDLE) return super.getBiome(x, y, z);
        final Source source = Objects.requireNonNull(this.source);
        final Palette biomes = sections(source).biomes()[sectionIndex(y)];
        final int id = biomes.get((x & 15) / 4, (y & 15) / 4, (z & 15) / 4);
        return Objects.requireNonNull(instance.registries().biome().getKey(id), () -> "biome " + id + " is not registered");
    }

    private int state(Source source, int x, int y, int z) {
        return sections(source).blocks()[sectionIndex(y)].get(x & 15, y & 15, z & 15);
    }

    /**
     * The palettes of {@code source}, through the cache entry this chunk took last, without the lock of
     * the cache, until the garbage collector takes that entry after its eviction; then from the cache.
     */
    private SynxPacketDecoder.Sections sections(Source source) {
        final WeakReference<SectionCache.Entry> reference = this.decoded;
        SectionCache.Entry entry = reference != null ? reference.get() : null;
        if (entry == null || !entry.key().equals(source.key())) {
            entry = loader.sections(source, instance);
            this.decoded = new WeakReference<>(entry);
        }
        return entry.sections();
    }

    private int sectionIndex(int y) {
        return (y >> 4) - minSection;
    }

    /**
     * Handle mode sends segment ① as it is, overlay mode the {@link #snapshot()} of its content, failed
     * mode the engine's packet of its air, which never changes. Each call counts as one send of its
     * mode ({@link SynxStreamStats}).
     */
    @Override
    public SendablePacket getFullDataPacket() {
        // Mode first: demotion publishes the new source before it switches the mode back to handle
        if (mode == Mode.HANDLE) return handlePacket();
        if (mode == Mode.FAILED) {
            loader.overlaySent();
            return super.getFullDataPacket();
        }
        lockWriteLock();
        try {
            if (mode == Mode.HANDLE) return handlePacket(); // demoted since the check above
            loader.overlaySent();
            return snapshot();
        } finally {
            unlockWriteLock();
        }
    }

    private BufferedPacket handlePacket() {
        final BufferedPacket packet = Objects.requireNonNull(source).packet();
        loader.handleSent(packet.length());
        return packet;
    }

    /**
     * The overlay content at the current {@code generation}, taken under the write-lock: the engine's
     * chunk packet with the client's block counts ({@link #counted}), wrapped for the connections to
     * frame once and share. The packet has its own copy of the section data, and the light arrays it
     * refers to are replaced, never written in place; so nothing that happens to the chunk afterwards,
     * a demotion that empties its sections included, reaches a packet already handed out, and a frame
     * dropped under memory pressure is framed again from the same packet.
     */
    private CachedPacket snapshot() {
        assert holdsWriteLock() : "taking the overlay content of chunk " + chunkX + "," + chunkZ + " without its write-lock";
        final long generation = this.generation.get();
        final long invalidations = this.invalidations.get();
        final SoftReference<Snapshot> reference = this.snapshot;
        final Snapshot current = reference != null ? reference.get() : null;
        if (current != null && current.generation() == generation && current.invalidations() == invalidations) {
            return current.packet();
        }
        final ChunkDataPacket packet = (ChunkDataPacket) ((CachedPacket) super.getFullDataPacket()).packet();
        final CachedPacket taken = new CachedPacket(counted(packet, instance.registries()));
        this.snapshot = new SoftReference<>(new Snapshot(generation, invalidations, taken));
        return taken;
    }

    /**
     * {@code packet} with the block count of each section as the client and the baker count it, air
     * of every kind left out ({@code ChunkEncoder}). The engine's packet counts every state but state 0
     * ({@code Palette#count}), so any section with cave air or void air would differ from segment ①.
     * The count is the short each section starts with, written over in a copy; the rest stays as it is.
     */
    private static ChunkDataPacket counted(ChunkDataPacket packet, Registries registries) {
        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(registries.biome().size());
        final ChunkData chunkData = packet.chunkData();
        final byte[] data = chunkData.data();
        final byte[] counted = data.clone();
        final NetworkBuffer input = NetworkBuffer.wrap(data, 0, data.length, registries);
        while (input.readableBytes() > 0) {
            final int start = (int) input.readIndex();
            final Palette blocks = input.read(type).blockStates();
            int count = blocks.count();
            for (int air : OTHER_AIR) {
                if (blocks.any(air)) count -= blocks.count(air);
            }
            counted[start] = (byte) (count >> 8);
            counted[start + 1] = (byte) count;
        }
        return new ChunkDataPacket(packet.chunkX(), packet.chunkZ(),
                new ChunkData(chunkData.heightmaps(), counted, chunkData.blockEntities()), packet.lightData());
    }

    /** Also takes the overlay snapshot again: whatever changed the sections did not change {@code generation}. */
    @Override
    public void invalidate() {
        invalidations.incrementAndGet();
        super.invalidate();
    }

    /**
     * Not while a light recomputation is queued or running with this chunk in its 3×3; dirty overlay
     * chunks stay until the overlay store has written them, which it is asked to.
     */
    @Override
    protected boolean readyToUnload() {
        if (relightPending()) return false;
        if (mode != Mode.OVERLAY || !dirty) return true;
        loader.tracker().unloadRequested(this);
        return false;
    }

    /** Spawns the static entities of a chunk of the region, on the thread loading it. */
    @Override
    protected void onLoad() {
        final Source source = this.source;
        if (!inRegion || source == null || source.staticEntities().isEmpty()) return;
        loader.spawnStatics(instance, chunkX, chunkZ, source.staticEntities());
    }

    @Override
    public @Nullable OverlayEncoding encodeOverlay(SynxSettings settings, long deadlineNanos) {
        final long wait = Math.min(deadlineNanos - System.nanoTime(), LOCK_WAIT_NANOS);
        if (!tryLockWriteLock(Duration.ofNanos(Math.max(0, wait)))) return null;
        try {
            final Source source = Objects.requireNonNull(this.source);
            final long generation = this.generation.get();
            if (mode == Mode.HANDLE) {
                // Nothing changed since the source: its record as it is
                final SynxEntities.Tables tables = SynxEntities.decode(source.slot().entityTables());
                return new OverlayEncoding(generation, new SynxChunkRecord(chunkX, chunkZ,
                        source.slot().packet().toArray(ValueLayout.JAVA_BYTE), navigation(source),
                        tables.blockEntities(), tables.staticEntities(), new byte[0]));
            }
            final ChunkDataPacket packet = (ChunkDataPacket) snapshot().packet();
            final List<SynxBlockEntity> blockEntities = new ArrayList<>(entries.size());
            for (Int2ObjectMap.Entry<Block> entry : Int2ObjectMaps.fastIterable(entries)) {
                blockEntities.add(blockEntity(entry.getIntKey(), entry.getValue()));
            }
            return new OverlayEncoding(generation, new SynxChunkRecord(chunkX, chunkZ,
                    SynxChunkPacket.encode(packet, settings, instance.registries()), navigation(source),
                    blockEntities, source.staticEntities(), new byte[0]));
        } finally {
            unlockWriteLock();
        }
    }

    /**
     * Takes the write-lock for at most 50 ms, as {@link #encodeOverlay} does: a tick thread judged dead
     * while it holds the lock must not keep the final flush past its budget. Without the lock the
     * chunk stays as it was, and the overlay store takes it up again, a dirty chunk to write, a clean
     * one to demote. Returns false then.
     */
    @Override
    public boolean written(long generation, OverlayHit hit, boolean demote) {
        final Source written = loader.source(hit.record(), new SectionCache.Key(hit.segment(), hit.offset()),
                instance.process().block());
        if (!tryLockWriteLock(Duration.ofNanos(LOCK_WAIT_NANOS))) {
            LOGGER.warn("Chunk {},{} stays as it was after its overlay record was written: its write-lock was held for 50 ms",
                    chunkX, chunkZ);
            return false;
        }
        try {
            this.source = written;
            if (this.generation.get() != generation) return true; // changed again since the encoding: still dirty
            this.dirty = false;
            if (demote && mode == Mode.OVERLAY) demote(written);
            return true;
        } finally {
            unlockWriteLock();
        }
    }

    /**
     * Handle to overlay, under the write-lock: segment ① into the sections, light included (without it
     * a new viewer gets an empty sky mask, a black chunk), segment ③ into the block entities.
     * {@code copyFrom} keeps each palette in the order segment ① has it, so the chunk encodes back to
     * the same bytes.
     */
    private void materialize() {
        assert holdsWriteLock() : "materializing chunk " + chunkX + "," + chunkZ + " without its write-lock";
        final Source source = Objects.requireNonNull(this.source);
        final SynxPacketDecoder.Full full = mapped(source, () -> SynxPacketDecoder.full(source.slot().packet(),
                instance.registries(), sections.size()));
        for (int i = 0; i < sections.size(); i++) {
            final Section section = sections.get(i);
            section.blockPalette().copyFrom(full.sections().blocks()[i]);
            final Palette biomes = full.sections().biomes()[i];
            // A direct biome palette of the packet is as wide as the biome registry needs, not the storage width
            if (biomes.bitsPerEntry() <= Palette.BIOME_PALETTE_MAX_BITS) section.biomePalette().copyFrom(biomes);
            else section.biomePalette().setAll(biomes::get);
            section.setSkyLight(Objects.requireNonNullElse(full.sky()[i], NO_LIGHT));
            section.setBlockLight(Objects.requireNonNullElse(full.block()[i], NO_LIGHT));
        }
        entries.clear();
        tickableMap.clear();
        for (Int2ObjectMap.Entry<BlockEntity> entry : Int2ObjectMaps.fastIterable(source.blockEntities())) {
            final int index = entry.getIntKey();
            final int y = CoordConversion.chunkBlockIndexGetY(index);
            final int state = full.sections().blocks()[sectionIndex(y)].get(
                    CoordConversion.chunkBlockIndexGetX(index), y & 15, CoordConversion.chunkBlockIndexGetZ(index));
            final Block block = entry.getValue().block(state);
            entries.put(index, block);
            if (block.handler() != null && block.handler().isTickable()) tickableMap.put(index, block);
        }
        // New heightmaps: a heightmap computed once, even on the empty sections of handle mode, never recomputes
        this.motionBlocking = new MotionBlockingHeightmap(this);
        this.worldSurface = new WorldSurfaceHeightmap(this);
        invalidate();
        this.mode = Mode.OVERLAY;
        loader.tracker().materialized(this);
    }

    /** Overlay back to handle on {@code source}, under the write-lock; the sections go back to empty. */
    private void demote(Source source) {
        for (Section section : sections) {
            section.clear();
            section.setSkyLight(NO_LIGHT);
            section.setBlockLight(NO_LIGHT);
        }
        entries.clear();
        tickableMap.clear();
        registerTickables(source);
        invalidate();
        this.snapshot = null;
        this.mode = Mode.HANDLE;
    }

    /**
     * The ticking handlers of segment ③ into {@code tickableMap}, their block state from the section
     * cache. Not for a chunk outside the region: the shard of its own region ticks it.
     */
    private void registerTickables(Source source) {
        if (!inRegion) return;
        for (Int2ObjectMap.Entry<BlockEntity> entry : Int2ObjectMaps.fastIterable(source.blockEntities())) {
            final BlockHandler handler = entry.getValue().handler();
            if (handler == null || !handler.isTickable()) continue;
            final int index = entry.getIntKey();
            final int x = CoordConversion.chunkBlockIndexGetX(index);
            final int y = CoordConversion.chunkBlockIndexGetY(index);
            final int z = CoordConversion.chunkBlockIndexGetZ(index);
            tickableMap.put(index, entry.getValue().block(state(source, x, y, z)));
        }
    }

    // ---- The runtime light (API-M3-005 运行时光照)

    /** Whether the runtime light writes this chunk: inside the region and not failed. */
    boolean lightWritable() {
        return inRegion && mode != Mode.FAILED;
    }

    /** The changes of block state since the chunk loaded; a recomputation holds its result to it. */
    long blockChanges() {
        return blockChanges.get();
    }

    /** Player to the {@code worldAge} it was last delivered this chunk or its light in ({@code delivered}). */
    Map<Player, Long> delivered() {
        return delivered;
    }

    /** Player to whether a resend is due whatever the next install finds ({@code light_stale}). */
    Map<Player, Boolean> lightStale() {
        return lightStale;
    }

    /**
     * The block palettes of every section for the snapshot of a recomputation, bottom up, under the
     * read-lock the caller holds: copies of the sections in overlay mode, the shared palettes of the
     * section cache in handle mode (read, never written), null in failed mode, which is air.
     */
    Palette @Nullable [] relightPalettes() {
        assert holdsReadLock() : "taking the blocks of chunk " + chunkX + "," + chunkZ + " without its read-lock";
        return switch (mode) {
            case OVERLAY -> {
                final Palette[] copies = new Palette[sections.size()];
                for (int i = 0; i < copies.length; i++) copies[i] = sections.get(i).blockPalette().clone();
                yield copies;
            }
            case HANDLE -> sections(Objects.requireNonNull(source)).blocks();
            case FAILED -> null;
        };
    }

    /**
     * The light of the chunk as a new viewer gets it, which a recomputation compares its result with:
     * segment ① in handle mode, the sections under the read-lock otherwise.
     */
    LightData currentLight() {
        final LightData handle = handleLight();
        if (handle != null) return handle;
        lockReadLock();
        try {
            return heldLight();
        } finally {
            unlockReadLock();
        }
    }

    /** Segment ①'s light in handle mode, null otherwise; mode first, as {@link #getFullDataPacket} reads it. */
    private @Nullable LightData handleLight() {
        if (mode != Mode.HANDLE) return null;
        final Source source = Objects.requireNonNull(this.source);
        return mapped(source, () -> SynxPacketDecoder.light(source.slot().packet(), instance.registries()));
    }

    /** {@link #handleLight}, the sections otherwise; the caller holds a lock of the chunk. */
    private LightData heldLight() {
        final LightData handle = handleLight();
        return handle != null ? handle : createLightData(false);
    }

    /**
     * Handle to overlay for its light (§11.1 为光照物化): the sections filled from the current source as it
     * is, {@code generation} and the dirty mark left as they are. Whether the world is writable does not
     * count: light is no change to the world. Waits at most {@code wait} for the write-lock.
     *
     * @return true if this call switched the chunk to overlay mode
     */
    boolean materializeForLight(Duration wait) {
        if (mode != Mode.HANDLE) return false;
        if (!tryLockWriteLock(wait)) return false;
        try {
            if (mode != Mode.HANDLE) return false;
            materialize();
            return true;
        } finally {
            unlockWriteLock();
        }
    }

    /**
     * What an install of new light did.
     *
     * @param light        the full light after the install, what a resend carries
     * @param materialized whether the chunk was still in handle mode and was materialized for it
     * @param sky          the sections whose sky light it swapped, bottom up, null where the chunk held that light already
     * @param block        the sections whose block light it swapped, as {@code sky}
     * @param swapped      whether it swapped any section; false leaves the chunk as it was
     */
    record Installed(LightData light, boolean materialized, byte[] @Nullable [] sky, byte[] @Nullable [] block,
                     boolean swapped) {
    }

    /**
     * Installs the new light of a recomputation (§11.1 光照装回), on the tick scheduling thread: each
     * section whose light the recomputation changed and the chunk does not hold yet takes the new
     * array, a new object that a packet already handed out does not see; the caches go,
     * {@code generation} goes up by one and the chunk is dirty. A handle chunk is materialized for its
     * light first. The recomputation of a neighbouring change may have installed the same light
     * already: then nothing is swapped and nothing else changes either. Only tries the write-lock: the
     * encode pool may hold it.
     *
     * @param sky   the new sky light by section, bottom up, null where it did not change
     * @param block the new block light by section, as {@code sky}
     * @return null without the write-lock, the result otherwise
     */
    @Nullable Installed installLight(byte[] @Nullable [] sky, byte[] @Nullable [] block) {
        if (!tryLockWriteLock(Duration.ZERO)) return null;
        try {
            final LightData held = heldLight();
            final int count = sections.size();
            final byte[][] heldSky = SynxPacketDecoder.sky(held, count), heldBlock = SynxPacketDecoder.block(held, count);
            final byte[][] swapSky = new byte[count][], swapBlock = new byte[count][];
            boolean swapped = false;
            for (int i = 0; i < count; i++) {
                if (sky[i] != null && !SynxRelight.sameLight(sky[i], heldSky[i])) {
                    swapSky[i] = sky[i];
                    swapped = true;
                }
                if (block[i] != null && !SynxRelight.sameLight(block[i], heldBlock[i])) {
                    swapBlock[i] = block[i];
                    swapped = true;
                }
            }
            if (!swapped) return new Installed(held, false, swapSky, swapBlock, false);
            final boolean materialized = mode == Mode.HANDLE;
            if (materialized) materialize();
            for (int i = 0; i < count; i++) {
                if (swapSky[i] != null) sections.get(i).setSkyLight(swapSky[i]);
                if (swapBlock[i] != null) sections.get(i).setBlockLight(swapBlock[i]);
            }
            invalidate();
            changed();
            return new Installed(createLightData(false), materialized, swapSky, swapBlock, true);
        } finally {
            unlockWriteLock();
        }
    }

    /**
     * The full light for a resend of an install that changed nothing: segment ① in handle mode, the
     * sections otherwise, under the write-lock, which is only tried. The write-lock because the engine
     * has no timed read-lock, and the install must not wait on the tick scheduling thread.
     *
     * @return null without the write-lock
     */
    @Nullable LightData lightForResend() {
        final LightData handle = handleLight();
        if (handle != null) return handle;
        if (!tryLockWriteLock(Duration.ZERO)) return null;
        try {
            return heldLight();
        } finally {
            unlockWriteLock();
        }
    }

    /**
     * Reads the mapping of {@code source}: a fault there ({@code API-M3-005} 映射读取出错) throws as
     * {@link SectionCache#get} throws it, an {@link IllegalStateException} the callers handle.
     */
    private static <T> T mapped(Source source, Supplier<T> read) {
        try {
            return read.get();
        } catch (InternalError e) {
            throw new IllegalStateException(source.key().file() + " faulted on read", e);
        }
    }

    private static SynxBlockEntity blockEntity(int index, Block block) {
        final int x = CoordConversion.chunkBlockIndexGetX(index);
        final int y = CoordConversion.chunkBlockIndexGetY(index);
        final int z = CoordConversion.chunkBlockIndexGetZ(index);
        final BlockHandler handler = block.handler();
        return new SynxBlockEntity(y >> 4, SynxBlockEntity.index(x, y, z),
                handler == null ? "" : handler.getKey().asString(),
                Objects.requireNonNullElse(block.nbt(), CompoundBinaryTag.empty()));
    }

    private static byte[] navigation(Source source) {
        return source.slot().navigation().toArray(ValueLayout.JAVA_BYTE);
    }
}
