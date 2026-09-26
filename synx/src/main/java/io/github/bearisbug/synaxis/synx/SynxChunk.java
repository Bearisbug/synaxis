package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.Section;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.heightmap.MotionBlockingHeightmap;
import io.github.bearisbug.synaxis.instance.heightmap.WorldSurfaceHeightmap;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.biome.Biome;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A chunk of a {@code .synx} map ({@code docs/DESIGN-M3.md} API-M3-005, {@code ADR-M3-016}). Handle,
 * overlay and failed mode are states of the one object, which switches in place under its write-lock.
 * <ul>
 *     <li>Handle: the sections of {@link DynamicChunk} stay empty and are neither read nor sent. Blocks
 *     come from the section cache of the loader, block entities from segment ③, and the full data
 *     packet is segment ① as it is, a {@link BufferedPacket} over the mapping.</li>
 *     <li>Overlay: the first change fills the sections from segment ① and segment ③, light included,
 *     and the chunk is a {@code DynamicChunk} from then on; the overlay store writes it out.</li>
 *     <li>Failed: a record that does not read loads as air with full sky light, read only.</li>
 * </ul>
 * A chunk outside the region ({@code bounds}) or in a world that is not writable is read only as well:
 * changes are ignored silently, as the engine ignores them on any read only chunk.
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
                  Int2ObjectMap<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities) {
    }

    private static final long LOCK_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final byte[] NO_LIGHT = new byte[0];
    private static final byte[] FULL_LIGHT = new byte[2048];
    /** The keys of a block entity record the block carries itself; the baker leaves them out of segment ①. */
    private static final List<String> RECORD_KEYS = List.of("id", "x", "y", "z", "keepPacked");

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

    /**
     * Outside the region, in failed mode, or with {@code synaxis.world-writable=false}. The engine
     * checks this in {@code InstanceContainer} only; {@link #setBlock} and {@link #setBiome} check it
     * again for the callers that come around it.
     */
    @Override
    public boolean isReadOnly() {
        return !inRegion || mode == Mode.FAILED || !loader.options().worldWritable() || super.isReadOnly();
    }

    @Override
    public void setBlock(int x, int y, int z, Block block, @Nullable BlockHandler.Placement placement,
                         @Nullable BlockHandler.Destroy destroy) {
        if (isReadOnly()) return;
        if (y < minSection * CHUNK_SECTION_SIZE || y >= maxSection * CHUNK_SECTION_SIZE) {
            super.setBlock(x, y, z, block, placement, destroy); // warns and changes nothing
            return;
        }
        if (mode == Mode.HANDLE) materialize();
        super.setBlock(x, y, z, block, placement, destroy);
        changed();
    }

    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        if (isReadOnly()) return;
        if (mode == Mode.HANDLE) materialize();
        super.setBiome(x, y, z, biome);
        changed();
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
            final SynxBlockEntity entity = source.blockEntities().get(CoordConversion.chunkBlockIndex(x, y, z));
            if (entity != null) return block(entity, state(source, x, y, z));
            if (condition == Condition.CACHED) return null;
        }
        return Objects.requireNonNullElse(Block.fromStateId(state(source, x, y, z)), Block.AIR);
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        if (mode != Mode.HANDLE) return super.getBiome(x, y, z);
        final Source source = Objects.requireNonNull(this.source);
        final Palette biomes = loader.sections(source, instance).biomes()[sectionIndex(y)];
        final int id = biomes.get((x & 15) / 4, (y & 15) / 4, (z & 15) / 4);
        return Objects.requireNonNull(instance.registries().biome().getKey(id), () -> "biome " + id + " is not registered");
    }

    private int state(Source source, int x, int y, int z) {
        return loader.sections(source, instance).blocks()[sectionIndex(y)].get(x & 15, y & 15, z & 15);
    }

    private int sectionIndex(int y) {
        return (y >> 4) - minSection;
    }

    /**
     * Handle mode sends segment ① as it is; overlay and failed mode send the packet the engine encodes.
     * Each call counts as one send of its mode ({@link SynxStreamStats}).
     */
    @Override
    public SendablePacket getFullDataPacket() {
        // Mode first: demotion publishes the new source before it switches the mode back to handle
        if (mode == Mode.HANDLE) {
            final BufferedPacket packet = Objects.requireNonNull(source).packet();
            loader.handleSent(packet.length());
            return packet;
        }
        loader.overlaySent();
        return super.getFullDataPacket();
    }

    /** Dirty overlay chunks stay until the overlay store has written them; it is asked to. */
    @Override
    protected boolean readyToUnload() {
        if (mode != Mode.OVERLAY || !dirty) return true;
        loader.tracker().unloadRequested(this);
        return false;
    }

    /** Spawns the static entities of a chunk of the region, on the thread loading it. */
    @Override
    protected void onLoad() {
        final Source source = this.source;
        if (!inRegion || source == null || source.staticEntities().isEmpty()) return;
        loader.spawnStatics(instance, source.staticEntities());
    }

    @Override
    public @Nullable OverlayEncoding encodeOverlay(SynxSettings settings, long deadlineNanos) {
        final long wait = Math.min(deadlineNanos - System.nanoTime(), LOCK_WAIT_NANOS);
        if (!tryLockWriteLock(Duration.ofNanos(Math.max(0, wait)))) return null;
        try {
            final Source source = this.source;
            if (source == null) return null;
            final long generation = this.generation.get();
            if (mode == Mode.HANDLE) {
                // Nothing changed since the source: its record as it is
                final SynxEntities.Tables tables = SynxEntities.decode(source.slot().entityTables());
                return new OverlayEncoding(generation, new SynxChunkRecord(chunkX, chunkZ,
                        source.slot().packet().toArray(ValueLayout.JAVA_BYTE), navigation(source),
                        tables.blockEntities(), tables.staticEntities(), new byte[0]));
            }
            final ChunkDataPacket packet = (ChunkDataPacket) ((CachedPacket) super.getFullDataPacket()).packet();
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

    @Override
    public void written(long generation, OverlayHit hit, boolean demote) {
        final Source written = loader.source(hit.record(), new SectionCache.Key(hit.segment(), hit.offset()));
        lockWriteLock();
        try {
            this.source = written;
            if (this.generation.get() != generation) return; // changed again since the encoding: still dirty
            this.dirty = false;
            if (demote && mode == Mode.OVERLAY) demote(written);
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
        final SynxPacketDecoder.Full decoded = SynxPacketDecoder.full(source.slot().packet(), instance.registries(),
                sections.size());
        for (int i = 0; i < sections.size(); i++) {
            final Section section = sections.get(i);
            section.blockPalette().copyFrom(decoded.sections().blocks()[i]);
            final Palette biomes = decoded.sections().biomes()[i];
            // A direct biome palette of the packet is as wide as the biome registry needs, not the storage width
            if (biomes.bitsPerEntry() <= Palette.BIOME_PALETTE_MAX_BITS) section.biomePalette().copyFrom(biomes);
            else section.biomePalette().setAll(biomes::get);
            section.setSkyLight(Objects.requireNonNullElse(decoded.sky()[i], NO_LIGHT));
            section.setBlockLight(Objects.requireNonNullElse(decoded.block()[i], NO_LIGHT));
        }
        entries.clear();
        tickableMap.clear();
        for (Int2ObjectMap.Entry<SynxBlockEntity> entry : Int2ObjectMaps.fastIterable(source.blockEntities())) {
            final int index = entry.getIntKey();
            final int y = CoordConversion.chunkBlockIndexGetY(index);
            final int state = decoded.sections().blocks()[sectionIndex(y)].get(
                    CoordConversion.chunkBlockIndexGetX(index), y & 15, CoordConversion.chunkBlockIndexGetZ(index));
            final Block block = block(entry.getValue(), state);
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
            section.blockPalette().fill(0);
            section.biomePalette().fill(0);
            section.setSkyLight(NO_LIGHT);
            section.setBlockLight(NO_LIGHT);
        }
        entries.clear();
        tickableMap.clear();
        registerTickables(source);
        invalidate();
        this.mode = Mode.HANDLE;
    }

    /**
     * The ticking handlers of segment ③ into {@code tickableMap}, their block state from the section
     * cache. Not for a chunk outside the region: the shard of its own region ticks it.
     */
    private void registerTickables(Source source) {
        if (!inRegion) return;
        for (Int2ObjectMap.Entry<SynxBlockEntity> entry : Int2ObjectMaps.fastIterable(source.blockEntities())) {
            final String namespace = entry.getValue().handler();
            if (namespace.isEmpty()) continue;
            if (!instance.process().block().getHandlerOrDummy(namespace).isTickable()) continue;
            final int index = entry.getIntKey();
            final int x = CoordConversion.chunkBlockIndexGetX(index);
            final int y = CoordConversion.chunkBlockIndexGetY(index);
            final int z = CoordConversion.chunkBlockIndexGetZ(index);
            tickableMap.put(index, block(entry.getValue(), state(source, x, y, z)));
        }
    }

    /**
     * The block of a segment ③ entry: its state, its NBT without the record keys (the form the baker
     * sent the client its tags from), and the handler of its namespace; a namespace the shard did not
     * register gets the engine's dummy, which keeps the namespace and shows the client nothing.
     */
    private Block block(SynxBlockEntity entity, int state) {
        Block block = Objects.requireNonNullElse(Block.fromStateId(state), Block.AIR);
        CompoundBinaryTag nbt = entity.nbt();
        for (String key : RECORD_KEYS) nbt = nbt.remove(key);
        block = block.withNbt(nbt);
        if (!entity.handler().isEmpty()) block = block.withHandler(instance.process().block().getHandlerOrDummy(entity.handler()));
        return block;
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
