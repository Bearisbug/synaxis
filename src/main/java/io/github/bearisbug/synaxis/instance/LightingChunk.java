package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.instance.light.Light;
import io.github.bearisbug.synaxis.instance.light.LightBlocks;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.instance.light.WorldView;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static io.github.bearisbug.synaxis.instance.light.LightCompute.EMPTY_CONTENT;

/**
 * A chunk that holds sky and block light in its sections and sends it with its packets.
 * <p>
 * Two paths compute that light. {@link #relight(Instance, Collection)} lights whole areas through
 * {@link LightEngine} on a copy of the blocks, with no instance-wide lock. A section whose light a block change or
 * {@link #onGenerate} marked for update is lit lazily by {@code relightSection}, under the instance monitor, when a
 * packet is built ({@link #createLightData}) or {@code Instance#getSkyLight}/{@code getBlockLight} reads it.
 */
public class LightingChunk extends DynamicChunk {

    private static final ExecutorService pool = Executors.newWorkStealingPool();
    // Chunks per side of the area one LightEngine call lights; each call also reads the ring of chunks around it
    private static final int RELIGHT_TILE = 8;

    private volatile @Nullable OcclusionData occlusionData;
    @SuppressWarnings("this-escape") // deliberate self registration during construction
    final CachedPacket partialLightCache = new CachedPacket(this::createLightPacket);
    private @Nullable LightData partialLightData;
    private @Nullable LightData fullLightData;

    private final ReentrantLock packetGenerationLock = new ReentrantLock();
    private final AtomicInteger resendTimer = new AtomicInteger(-1);

    private boolean doneInit = false;

    enum LightType {
        SKY,
        BLOCK
    }

    private enum QueueType {
        INTERNAL,
        EXTERNAL
    }

    /** The heightmaps and the packet caches, the light ones included. */
    @Override
    public void invalidate() {
        super.invalidate();
        invalidateLight();
    }

    /**
     * The packet caches, for a change of light alone: the blocks of this chunk are what they were, so
     * the heightmaps are not computed again for the next packet.
     */
    private void invalidateLight() {
        this.partialLightCache.invalidate();
        this.chunkCache.invalidate();
        this.partialLightData = null;
        this.fullLightData = null;
    }

    public LightingChunk(Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ);
    }

    protected LightingChunk(Instance instance, int chunkX, int chunkZ, List<Section> sections) {
        super(instance, chunkX, chunkZ, sections);
    }

    private void invalidateNeighborsSection(int coordinate) {
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Chunk neighborChunk = instance.getChunk(chunkX + i, chunkZ + j);
                if (neighborChunk == null) continue;

                if (neighborChunk instanceof LightingChunk light) {
                    light.invalidateLight();
                }

                for (int k = -1; k <= 1; k++) {
                    if (k + coordinate < neighborChunk.getMinSection() || k + coordinate >= neighborChunk.getMaxSection())
                        continue;
                    neighborChunk.getSection(k + coordinate).invalidate();
                }
            }
        }
    }

    private void invalidateResendDelay() {
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Chunk neighborChunk = instance.getChunk(chunkX + i, chunkZ + j);
                if (neighborChunk instanceof LightingChunk light) {
                    light.resendTimer.set(instance.process().config().sendLightAfterBlockPlacementDelay());
                }
            }
        }
    }

    @Override
    public void setBlock(int x, int y, int z, Block block,
                         @Nullable BlockHandler.Placement placement,
                         @Nullable BlockHandler.Destroy destroy) {
        super.setBlock(x, y, z, block, placement, destroy);
        this.occlusionData = null;

        // Invalidate neighbor chunks, since they can be updated by this block change
        int coordinate = CoordConversion.globalToChunk(y);
        if (doneInit) {
            invalidateNeighborsSection(coordinate);
            invalidateResendDelay();
            this.partialLightCache.invalidate();
        }
    }

    public void sendLighting() {
        if (!isLoaded()) return;
        sendPacketToViewers(partialLightCache);
    }

    @Override
    protected void onLoad() {
        doneInit = true;
    }

    @Override
    public void onGenerate() {
        super.onGenerate();

        for (int section = minSection; section < maxSection; section++) {
            getSection(section).invalidate();
        }

        invalidate();

        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Chunk neighborChunk = instance.getChunk(chunkX + i, chunkZ + j);
                if (neighborChunk == null) continue;

                if (neighborChunk instanceof LightingChunk light) {
                    if (light.doneInit) {
                        light.resendTimer.set(20);
                        light.invalidateLight();

                        for (int section = minSection; section < maxSection; section++) {
                            light.getSection(section).invalidate();
                        }
                    }
                }
            }
        }
    }

    protected OcclusionData getOcclusionData() {
        assertReadLock();
        final OcclusionData currentOcclusionData = this.occlusionData;
        if (currentOcclusionData != null) return currentOcclusionData;

        final int[] occlusionMap = new int[CHUNK_SIZE_X * CHUNK_SIZE_Z];

        int minY = instance.getCachedDimensionType().minY();
        int highestBlock = minY - 1;

        int startY = Heightmap.getHighestBlockSection(this);

        for (int x = 0; x < CHUNK_SIZE_X; x++) {
            for (int z = 0; z < CHUNK_SIZE_Z; z++) {
                int height = startY;
                Block above = Block.AIR;
                while (height >= minY) {
                    Block block = getBlock(x, height, z, Condition.TYPE);
                    if (block != Block.AIR) highestBlock = Math.max(highestBlock, height);
                    if (LightBlocks.skyEdgeOccluded(above, block)) break;
                    above = block;
                    height--;
                }
                occlusionMap[z << 4 | x] = (height + 1);
            }
        }

        var occlusionData = new OcclusionData(highestBlock, occlusionMap);
        // While we only assert that we are in a read-lock, this should still be OK because even if two threads concurrently
        // compute the occlusionData, then both results will be the same, and the differing OcclusionData identites are of no concern.
        this.occlusionData = occlusionData;
        return occlusionData;
    }

    // Lazy compute occlusion map
    public int[] getOcclusionMap() {
        return getOcclusionData().occlusionMap();
    }

    @Override
    protected LightData createLightData(boolean requiredFullChunk) {
        packetGenerationLock.lock();
        try {
            if (requiredFullChunk) {
                if (fullLightData != null) {
                    return fullLightData;
                }
            } else {
                if (partialLightData != null) {
                    return partialLightData;
                }
            }

            BitSet skyMask = new BitSet();
            BitSet blockMask = new BitSet();
            BitSet emptySkyMask = new BitSet();
            BitSet emptyBlockMask = new BitSet();
            List<byte[]> skyLights = new ArrayList<>();
            List<byte[]> blockLights = new ArrayList<>();

            int chunkMin = instance.getCachedDimensionType().minY();
            int highestNeighborBlock = instance.getCachedDimensionType().minY();
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    Chunk neighborChunk = instance.getChunk(chunkX + i, chunkZ + j);
                    if (neighborChunk == null) continue;

                    if (neighborChunk instanceof LightingChunk light) {
                        light.lockReadLock();
                        try {
                            highestNeighborBlock = Math.max(highestNeighborBlock, light.getOcclusionData().highestBlock);
                        } finally {
                            light.unlockReadLock();
                        }
                    }
                }
            }

            int index = 0;
            for (Section section : sections) {
                // Bit i of the light masks is section minSection - 1 + i: bit 0 is the section below the world
                final int bit = index + 1;
                boolean wasUpdatedBlock = false;
                boolean wasUpdatedSky = false;

                if (section.blockLight().requiresUpdate()) {
                    relightSection(instance, this.chunkX, index + minSection, chunkZ, LightType.BLOCK);
                    wasUpdatedBlock = true;
                } else if (requiredFullChunk || section.blockLight().requiresSend()) {
                    wasUpdatedBlock = true;
                }

                if (section.skyLight().requiresUpdate()) {
                    relightSection(instance, this.chunkX, index + minSection, chunkZ, LightType.SKY);
                    wasUpdatedSky = true;
                } else if (requiredFullChunk || section.skyLight().requiresSend()) {
                    wasUpdatedSky = true;
                }

                final int sectionMinY = index * 16 + chunkMin;

                if (wasUpdatedSky && this.instance.getCachedDimensionType().hasSkylight() && sectionMinY <= highestNeighborBlock + 16) {
                    final byte[] skyLight = section.skyLight().array();

                    if (skyLight.length != 0 && skyLight != EMPTY_CONTENT) {
                        skyLights.add(skyLight);
                        skyMask.set(bit);
                    } else {
                        emptySkyMask.set(bit);
                    }
                }

                if (wasUpdatedBlock) {
                    final byte[] blockLight = section.blockLight().array();

                    if (blockLight.length != 0 && blockLight != EMPTY_CONTENT) {
                        blockLights.add(blockLight);
                        blockMask.set(bit);
                    } else {
                        emptyBlockMask.set(bit);
                    }
                }
                index++;
            }

            LightData lightData = new LightData(skyMask, blockMask,
                    emptySkyMask, emptyBlockMask,
                    skyLights, blockLights);

            if (requiredFullChunk) {
                this.fullLightData = lightData;
            } else {
                this.partialLightData = lightData;
            }


            return lightData;
        } finally {
            packetGenerationLock.unlock();
        }
    }

    @Override
    public void tick(long time) {
        super.tick(time);

        if (doneInit && resendTimer.get() > 0) {
            if (resendTimer.decrementAndGet() == 0) {
                sendLighting();
            }
        }
    }

    // A point here is a section: x and z are chunk coordinates, y the section y (as Light#getNeighbors builds them)
    private static void flushQueue(Instance instance, Set<Point> queue, LightType type, QueueType queueType) {
        Set<Point> newQueue = ConcurrentHashMap.newKeySet();

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        Light.LightLookup lightLookup = (x, y, z) -> {
            Chunk chunk = instance.getChunk(x, z);
            if (chunk == null) return null;
            if (!(chunk instanceof LightingChunk lighting)) return null;
            if (y - lighting.getMinSection() < 0 || y - lighting.getMaxSection() >= 0) return null;
            final Section section = lighting.getSection(y);
            return switch (type) {
                case BLOCK -> section.blockLight();
                case SKY -> section.skyLight();
            };
        };

        Light.PaletteLookup paletteLookup = (x, y, z) -> {
            Chunk chunk = instance.getChunk(x, z);
            if (chunk == null) return null;
            if (!(chunk instanceof LightingChunk lighting)) return null;
            if (y - lighting.getMinSection() < 0 || y - lighting.getMaxSection() >= 0) return null;
            return chunk.getSection(y).blockPalette();
        };

        for (Point point : queue) {
            Chunk chunk = instance.getChunk(point.blockX(), point.blockZ());
            if (!(chunk instanceof LightingChunk lightingChunk)) continue;

            Section section = chunk.getSection(point.blockY());

            Light light = switch (type) {
                case BLOCK -> section.blockLight();
                case SKY -> section.skyLight();
            };

            final Palette blockPalette = section.blockPalette();
            final Runnable computation = () -> {
                try {
                    final Set<Point> toAdd;
                    lightingChunk.lockReadLock();
                    try {
                        toAdd = switch (queueType) {
                            case INTERNAL -> light.calculateInternal(blockPalette,
                                    chunk.getChunkX(), point.blockY(), chunk.getChunkZ(),
                                    lightingChunk.getOcclusionMap(), chunk.instance.getCachedDimensionType().maxY(),
                                    lightLookup);
                            case EXTERNAL -> light.calculateExternal(blockPalette,
                                    Light.getNeighbors(chunk, point.blockY()),
                                    lightLookup, paletteLookup);
                        };
                    } finally {
                        lightingChunk.unlockReadLock();
                    }

                    light.flip();
                    newQueue.addAll(toAdd);
                } catch (Exception e) {
                    lightingChunk.getInstance().process().exception().handleException(e);
                }
            };

            if (lightingChunk.holdsWriteLock()) {
                // The caller already owns this chunk's write-lock (a BlockHandler reading light from
                // within Chunk#setBlock does). Handing the section to the pool would deadlock: the pool
                // thread blocks on the read-lock while the caller blocks in join() below. The write-lock
                // grants read access, so compute it on the calling thread instead.
                computation.run();
            } else {
                tasks.add(CompletableFuture.runAsync(computation, pool));
            }
        }

        tasks.forEach(CompletableFuture::join);

        if (!newQueue.isEmpty()) {
            flushQueue(instance, newQueue, type, QueueType.EXTERNAL);
        }
    }

    /**
     * Relights the specified chunks and the loaded chunks next to them, whose light a change at the border reaches.
     * <p>
     * The blocks are copied under each chunk's read lock, then {@link LightEngine} lights tiles of
     * {@value #RELIGHT_TILE}x{@value #RELIGHT_TILE} chunks in parallel on the copy. No instance-wide lock is taken.
     * <p>
     * Call it only while no block of these chunks or of the chunks around them changes, for instance before the
     * instance is registered: a block set between the copy and the write-back is lit as it was before, and the
     * write-back marks the section valid, so that light stays stale until the next change there.
     *
     * @param instance the instance
     * @param chunks   the chunks to relight
     * @return the chunks which have been relighted
     */
    public static List<Chunk> relight(Instance instance, Collection<Chunk> chunks) {
        final Map<Long, LightingChunk> targets = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            if (!(chunk instanceof LightingChunk)) continue;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    if (instance.getChunk(chunk.chunkX + i, chunk.chunkZ + j) instanceof LightingChunk lighting) {
                        targets.putIfAbsent(CoordConversion.chunkIndex(lighting.chunkX, lighting.chunkZ), lighting);
                    }
                }
            }
        }
        if (targets.isEmpty()) return List.of();

        final BlockSnapshot snapshot = BlockSnapshot.of(instance, targets.values());
        final Map<Long, List<LightingChunk>> tiles = new HashMap<>();
        for (LightingChunk chunk : targets.values()) {
            final long tile = CoordConversion.chunkIndex(Math.floorDiv(chunk.chunkX, RELIGHT_TILE), Math.floorDiv(chunk.chunkZ, RELIGHT_TILE));
            tiles.computeIfAbsent(tile, _ -> new ArrayList<>()).add(chunk);
        }
        final List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (List<LightingChunk> tile : tiles.values()) {
            tasks.add(CompletableFuture.runAsync(() -> relightTile(snapshot, tile), pool));
        }
        tasks.forEach(CompletableFuture::join);
        return new ArrayList<>(targets.values());
    }

    private static void relightTile(BlockSnapshot snapshot, List<LightingChunk> tile) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (LightingChunk chunk : tile) {
            minX = Math.min(minX, chunk.chunkX);
            minZ = Math.min(minZ, chunk.chunkZ);
            maxX = Math.max(maxX, chunk.chunkX);
            maxZ = Math.max(maxZ, chunk.chunkZ);
        }
        final LightResult result = LightEngine.computeArea(snapshot.around(minX, minZ, maxX, maxZ), minX, minZ, maxX, maxZ);
        for (LightingChunk chunk : tile) {
            for (int sectionY = chunk.minSection; sectionY < chunk.maxSection; sectionY++) {
                final Section section = chunk.getSection(sectionY);
                section.skyLight().set(result.sky(chunk.chunkX, sectionY, chunk.chunkZ));
                section.blockLight().set(result.block(chunk.chunkX, sectionY, chunk.chunkZ));
            }
            chunk.invalidateLight();
        }
    }

    /** The block palettes of a set of chunks and their neighbours, copied so that lighting them needs no lock. */
    private static final class BlockSnapshot {
        private final int minY, height;
        private final Long2ObjectMap<Palette[]> chunks = new Long2ObjectOpenHashMap<>();

        private BlockSnapshot(int minY, int height) {
            this.minY = minY;
            this.height = height;
        }

        static BlockSnapshot of(Instance instance, Collection<LightingChunk> targets) {
            final var dimension = instance.getCachedDimensionType();
            final BlockSnapshot snapshot = new BlockSnapshot(dimension.minY(), dimension.height());
            for (LightingChunk target : targets) {
                for (int i = -1; i <= 1; i++) {
                    for (int j = -1; j <= 1; j++) {
                        final long index = CoordConversion.chunkIndex(target.chunkX + i, target.chunkZ + j);
                        if (snapshot.chunks.containsKey(index)) continue;
                        if (!(instance.getChunk(target.chunkX + i, target.chunkZ + j) instanceof LightingChunk chunk)) continue;
                        final Palette[] palettes = new Palette[chunk.maxSection - chunk.minSection];
                        chunk.lockReadLock();
                        try {
                            for (int k = 0; k < palettes.length; k++) {
                                palettes[k] = chunk.getSection(chunk.minSection + k).blockPalette().clone();
                            }
                        } finally {
                            chunk.unlockReadLock();
                        }
                        snapshot.chunks.put(index, palettes);
                    }
                }
            }
            return snapshot;
        }

        /** The copied chunks of {@code minX..maxX} by {@code minZ..maxZ} and the ring around it, indexed by array. */
        WorldView around(int minX, int minZ, int maxX, int maxZ) {
            final int originX = minX - 1, originZ = minZ - 1, width = maxX - minX + 3, depth = maxZ - minZ + 3;
            final Palette[] @Nullable [] area = new Palette[width * depth][];
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    area[z * width + x] = chunks.get(CoordConversion.chunkIndex(originX + x, originZ + z));
                }
            }
            return new WorldView() {
                @Override
                public int minY() {
                    return minY;
                }

                @Override
                public int height() {
                    return height;
                }

                @Override
                public boolean hasChunk(int chunkX, int chunkZ) {
                    final int x = chunkX - originX, z = chunkZ - originZ;
                    return x >= 0 && x < width && z >= 0 && z < depth && area[z * width + x] != null;
                }

                @Override
                public Block getBlock(int x, int y, int z) {
                    final Palette[] palettes = Objects.requireNonNull(area[((z >> 4) - originZ) * width + (x >> 4) - originX]);
                    final int localY = y - minY;
                    return Objects.requireNonNull(Block.fromStateId(palettes[localY >> 4].get(x & 15, localY & 15, z & 15)));
                }
            };
        }
    }

    private static Set<Point> getNearbyRequired(Instance instance, Point point, LightType type) {
        Set<Point> collected = new HashSet<>();
        collected.add(point);

        int highestRegionPoint = instance.getCachedDimensionType().minY() - 1;

        for (int x = point.blockX() - 1; x <= point.blockX() + 1; x++) {
            for (int z = point.blockZ() - 1; z <= point.blockZ() + 1; z++) {
                Chunk chunkCheck = instance.getChunk(x, z);
                if (chunkCheck == null) continue;

                if (chunkCheck instanceof LightingChunk lighting) {
                    lighting.lockReadLock();
                    try {
                        highestRegionPoint = Math.max(highestRegionPoint, lighting.getOcclusionData().highestBlock);
                    } finally {
                        lighting.unlockReadLock();
                    }
                }
            }
        }

        for (int x = point.blockX() - 1; x <= point.blockX() + 1; x++) {
            for (int z = point.blockZ() - 1; z <= point.blockZ() + 1; z++) {
                Chunk chunkCheck = instance.getChunk(x, z);
                if (chunkCheck == null) continue;

                for (int y = point.blockY() - 1; y <= point.blockY() + 1; y++) {
                    Point sectionPosition = new Vec(x, y, z);
                    int sectionHeight = 16 * y;
                    if ((sectionHeight + 16) > highestRegionPoint && type == LightType.SKY) continue;

                    if (sectionPosition.blockY() < chunkCheck.getMaxSection() && sectionPosition.blockY() >= chunkCheck.getMinSection()) {
                        Section s = chunkCheck.getSection(sectionPosition.blockY());
                        if (type == LightType.BLOCK && !s.blockLight().requiresUpdate()) continue;
                        if (type == LightType.SKY && !s.skyLight().requiresUpdate()) continue;

                        collected.add(sectionPosition);
                    }
                }
            }
        }

        return collected;
    }

    // A point here is a section: x and z are chunk coordinates, y the section y (as Light#getNeighbors builds them)
    private static Set<Point> collectRequiredNearby(Instance instance, Point point, LightType type) {
        final Set<Point> found = new HashSet<>();
        final ArrayDeque<Point> toCheck = new ArrayDeque<>();

        toCheck.add(point);
        found.add(point);

        while (!toCheck.isEmpty()) {
            final Point current = toCheck.poll();
            final Set<Point> nearby = getNearbyRequired(instance, current, type);
            nearby.forEach(p -> {
                if (!found.contains(p)) {
                    found.add(p);
                    toCheck.add(p);
                }
            });
        }

        return found;
    }

    static void relightSection(Instance instance, int chunkX, int sectionY, int chunkZ) {
        relightSection(instance, chunkX, sectionY, chunkZ, LightType.BLOCK);
        relightSection(instance, chunkX, sectionY, chunkZ, LightType.SKY);
    }

    private static void relightSection(Instance instance, int chunkX, int sectionY, int chunkZ, LightType type) {
        Chunk c = instance.getChunk(chunkX, chunkZ);
        if (c == null) return;
        if (!(c instanceof LightingChunk)) return;

        synchronized (instance) {
            Set<Point> collected = collectRequiredNearby(instance, new Vec(chunkX, sectionY, chunkZ), type);
            flushQueue(instance, collected, type, QueueType.INTERNAL);
        }
    }

    @Override
    public Chunk copy(Instance instance, int chunkX, int chunkZ) {
        assertReadLock();
        var sections = this.sections.stream().map(Section::clone).toList();
        LightingChunk lightingChunk = new LightingChunk(instance, chunkX, chunkZ, sections);
        lightingChunk.entries.putAll(entries);
        lightingChunk.tickableMap.putAll(tickableMap);
        return lightingChunk;
    }

    @Override
    public boolean isLoaded() {
        return super.isLoaded() && doneInit;
    }

    protected record OcclusionData(int highestBlock, int[] occlusionMap) {
    }
}
