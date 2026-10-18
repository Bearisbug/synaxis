package io.github.bearisbug.synaxis.instance;

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.instance.InstanceBlockUpdateEvent;
import io.github.bearisbug.synaxis.event.instance.InstanceChunkLoadEvent;
import io.github.bearisbug.synaxis.event.instance.InstanceChunkUnloadEvent;
import io.github.bearisbug.synaxis.event.player.PlayerBlockBreakEvent;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockEntityType;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.rule.BlockPlacementRule;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.instance.generator.GeneratorImpl;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockEntityDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.MultiBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldEventPacket;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.network.PacketSendingUtils;
import io.github.bearisbug.synaxis.utils.async.AsyncUtils;
import io.github.bearisbug.synaxis.instance.block.BlockUtils;
import io.github.bearisbug.synaxis.utils.validate.Check;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.worldevent.WorldEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.vectrix.flare.fastutil.Long2ObjectSyncMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.github.bearisbug.synaxis.instance.ChunkUtils.isLoaded;

/**
 * An instance that owns its chunks.
 */
public class InstanceContainer extends Instance {
    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceContainer.class);

    private static final NoopChunkLoaderImpl DEFAULT_LOADER = NoopChunkLoaderImpl.INSTANCE;

    // docs/DESIGN-M3.md API-M3-006
    private static final int MAX_UNLOADS_PER_TICK = 256;

    // vanilla's NeighborUpdater.UPDATE_ORDER
    private static final BlockFace[] BLOCK_UPDATE_FACES = new BlockFace[]{
            BlockFace.WEST, BlockFace.EAST, BlockFace.BOTTOM, BlockFace.TOP, BlockFace.NORTH, BlockFace.SOUTH
    };

    // the chunk generator used, can be null
    private volatile @Nullable Generator generator;
    // (chunk index -> chunk) map, contains all the chunks in the instance
    // used as a monitor when access is required
    private final Long2ObjectSyncMap<Chunk> chunks = Long2ObjectSyncMap.hashmap();
    private final Map<Long, CompletableFuture<Chunk>> loadingChunks = new ConcurrentHashMap<>();

    // guarded by the instance monitor, same as UNSAFE_setBlock
    private final Map<BlockVec, Block> currentlyChangingBlocks = new HashMap<>();

    // the chunk loader, used when trying to load/save a chunk from another source
    private volatile ChunkLoader chunkLoader;

    // used to automatically enable the chunk loading or not
    private volatile boolean autoChunkLoad = true;

    // used to supply a new chunk object at a position when requested
    private volatile ChunkSupplier chunkSupplier;

    // Fields for instance copy
    protected InstanceContainer srcInstance; // only present if this instance has been created using a copy
    private volatile long lastBlockChangeTime; // Time at which the last block change happened (#setBlock)

    public InstanceContainer(ServerProcess process, UUID uuid, RegistryKey<DimensionType> dimensionType) {
        this(process, uuid, dimensionType, null, dimensionType.key());
    }

    public InstanceContainer(ServerProcess process, UUID uuid, RegistryKey<DimensionType> dimensionType, Key dimensionName) {
        this(process, uuid, dimensionType, null, dimensionName);
    }

    public InstanceContainer(ServerProcess process, UUID uuid, RegistryKey<DimensionType> dimensionType, @Nullable ChunkLoader loader) {
        this(process, uuid, dimensionType, loader, dimensionType.key());
    }

    @SuppressWarnings("this-escape") // deliberate self registration during construction
    public InstanceContainer(
            ServerProcess process,
            UUID uuid,
            RegistryKey<DimensionType> dimensionType,
            @Nullable ChunkLoader loader,
            Key dimensionName
    ) {
        super(process, uuid, dimensionType, dimensionName);
        setChunkSupplier(DynamicChunk::new);
        setChunkLoader(Objects.requireNonNullElse(loader, DEFAULT_LOADER));
        this.chunkLoader.loadInstance(this);
        // last block change starts at instance creation time
        refreshLastBlockChangeTime();
    }

    @Override
    public void setBlock(int x, int y, int z, Block block, boolean doBlockUpdates) {
        Chunk chunk = getChunkAt(x, z);
        if (chunk == null) {
            Check.stateCondition(!hasEnabledAutoChunkLoad(),
                    "Tried to set a block to an unloaded chunk with auto chunk load disabled");
            chunk = loadChunk(CoordConversion.globalToChunk(x), CoordConversion.globalToChunk(z)).join();
        }
        if (isLoaded(chunk)) UNSAFE_setBlock(chunk, x, y, z, block, null, null, doBlockUpdates, 0);
    }

    /**
     * Sets a block at the specified position.
     * <p>
     * Unsafe because the method is not synchronized and it does not verify if the chunk is loaded or not.
     *
     * @param chunk the {@link Chunk} which should be loaded
     * @param x     the block X
     * @param y     the block Y
     * @param z     the block Z
     * @param block the block to place
     * @return false if the block was not changed
     */
    private synchronized boolean UNSAFE_setBlock(Chunk chunk, int x, int y, int z, Block block,
                                                 @Nullable BlockHandler.Placement placement, @Nullable BlockHandler.Destroy destroy,
                                                 boolean doBlockUpdates, int updateDistance) {
        if (chunk.isReadOnly()) return false;
        final DimensionType dim = getCachedDimensionType();
        if (y >= dim.maxY() || y < dim.minY()) {
            LOGGER.warn("tried to set a block outside the world bounds, should be within [{}, {}): {}", dim.minY(), dim.maxY(), y);
            return false;
        }

        chunk.lockWriteLock();
        try {
            // Refresh the last block change time
            this.lastBlockChangeTime = System.nanoTime();
            final BlockVec blockPosition = new BlockVec(x, y, z);
            if (isAlreadyChanged(blockPosition, block)) { // do NOT change the block again.
                // Avoids StackOverflowExceptions when onDestroy tries to destroy the block itself
                // This can happen with nether portals which break the entire frame when a portal block is broken
                return false;
            }
            this.currentlyChangingBlocks.put(blockPosition, block);

            // Change id based on neighbors
            final BlockPlacementRule blockPlacementRule = process().block().getBlockPlacementRule(block);
            if (placement != null && blockPlacementRule != null && doBlockUpdates) {
                BlockPlacementRule.PlacementState rulePlacement;
                if (placement instanceof BlockHandler.PlayerPlacement pp) {
                    rulePlacement = new BlockPlacementRule.PlacementState(
                            this, block, pp.getBlockFace(), blockPosition,
                            new Vec(pp.getCursorX(), pp.getCursorY(), pp.getCursorZ()),
                            pp.getPlayer().getPosition(),
                            pp.getPlayer().getItemInHand(pp.getHand()),
                            pp.getPlayer().isSneaking()
                    );
                } else {
                    rulePlacement = new BlockPlacementRule.PlacementState(
                            this, block, null, blockPosition,
                            null, null, null,
                            false
                    );
                }

                final Block placed = blockPlacementRule.blockPlace(rulePlacement);
                if (placed == null) {
                    // Cancelled before the chunk sees it: nothing changed in this tick
                    this.currentlyChangingBlocks.remove(blockPosition);
                    return false;
                }
                // The handler of the placed block is told about the block the rule chose
                if (!placed.equals(block)) placement = withBlock(placement, placed);
                block = placed;
            }

            // Set the block
            try {
                chunk.setBlock(x, y, z, block, placement, destroy);
            } catch (RuntimeException e) {
                // A handler threw once the chunk had the block: the viewers still get what the chunk holds
                chunk.sendPacketToViewers(new BlockChangePacket(blockPosition, chunk.getBlock(x, y, z, Condition.TYPE).stateId()));
                throw e;
            }

            // Refresh neighbors since a new block has been placed
            if (doBlockUpdates) {
                executeNeighboursBlockPlacementRule(blockPosition, updateDistance);
            }

            // Refresh player chunk block
            {
                chunk.sendPacketToViewers(new BlockChangePacket(blockPosition, block.stateId()));
                BlockEntityType blockEntityType = block.blockEntityType();
                if (blockEntityType != null) {
                    final CompoundBinaryTag data = BlockUtils.extractClientNbt(block);
                    chunk.sendPacketToViewers(new BlockEntityDataPacket(blockPosition, blockEntityType, data));
                }
            }
            process().eventHandler().call(new InstanceBlockUpdateEvent(this, blockPosition, block));
            return true;
        } finally {
            chunk.unlockWriteLock();
        }
    }

    private static BlockHandler.Placement withBlock(BlockHandler.Placement placement, Block block) {
        if (placement instanceof BlockHandler.PlayerPlacement pp) {
            return new BlockHandler.PlayerPlacement(block, pp.getPreviousBlock(), pp.getInstance(), pp.getBlockPosition(),
                    pp.getPlayer(), pp.getHand(), pp.getBlockFace(), pp.getCursorX(), pp.getCursorY(), pp.getCursorZ());
        }
        return new BlockHandler.Placement(block, placement.getPreviousBlock(), placement.getInstance(), placement.getBlockPosition());
    }

    @Override
    public boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates) {
        final Point blockPosition = placement.getBlockPosition();
        final Chunk chunk = getChunkAt(blockPosition);
        if (!isLoaded(chunk)) return false;
        return UNSAFE_setBlock(chunk, blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(),
                placement.getBlock(), placement, null, doBlockUpdates, 0);
    }

    @Override
    public boolean breakBlock(Player player, Point blockPosition, BlockFace blockFace, boolean doBlockUpdates) {
        final Chunk chunk = getChunkAt(blockPosition);
        Objects.requireNonNull(chunk, "You cannot break blocks in a null chunk!");
        if (chunk.isReadOnly()) return false;
        if (!isLoaded(chunk)) return false;

        final Block block = getBlock(blockPosition);
        final int x = blockPosition.blockX();
        final int y = blockPosition.blockY();
        final int z = blockPosition.blockZ();
        if (block.air()) {
            // The player has a wrong version of this block; correct just that block instead of resending the chunk.
            player.sendPacket(new BlockChangePacket(blockPosition, block));
            return false;
        }
        PlayerBlockBreakEvent blockBreakEvent = new PlayerBlockBreakEvent(player, this, block, Block.AIR, blockPosition.asBlockVec(), blockFace);
        process().eventHandler().call(blockBreakEvent);
        final boolean allowed = !blockBreakEvent.isCancelled();
        if (allowed) {
            // Break or change the broken block based on event result
            final Block resultBlock = blockBreakEvent.getResultBlock();
            UNSAFE_setBlock(chunk, x, y, z, resultBlock, null,
                    new BlockHandler.PlayerDestroy(block, resultBlock, this, blockPosition, player), doBlockUpdates, 0);
            // Send the block break effect packet
            PacketSendingUtils.sendGroupedPacket(chunk.getViewers(),
                    new WorldEventPacket(WorldEvent.PARTICLES_DESTROY_BLOCK.id(), blockPosition, block.stateId(), false),
                    // Prevent the block breaker to play the particles and sound two times
                    (viewer) -> !viewer.equals(player));
        }
        return allowed;
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        final Chunk chunk = getChunk(chunkX, chunkZ);
        if (chunk != null) {
            // Chunk already loaded
            return CompletableFuture.completedFuture(chunk);
        }
        return retrieveChunk(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ) {
        final Chunk chunk = getChunk(chunkX, chunkZ);
        if (chunk != null) {
            // Chunk already loaded
            return CompletableFuture.completedFuture(chunk);
        }
        return hasEnabledAutoChunkLoad() ? retrieveChunk(chunkX, chunkZ) : AsyncUtils.empty();
    }

    @Override
    public void unloadChunk(Chunk chunk) {
        final List<Entity> entities = new ArrayList<>();
        synchronized (this) {
            if (!isLoaded(chunk)) return;
            final int chunkX = chunk.getChunkX();
            final int chunkZ = chunk.getChunkZ();
            // An unloaded chunk loses its dispatcher partition, and with it every player standing in
            // it: nothing would ever tick them again, so the chunk has to stay
            final boolean onlinePlayer = getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.PLAYERS)
                    .stream().anyMatch(Player::isOnline);
            Check.stateCondition(onlinePlayer, "You cannot unload a chunk with players inside.");
            chunk.sendPacketToViewers(new UnloadChunkPacket(chunkX, chunkZ));
            process().eventHandler().call(new InstanceChunkUnloadEvent(this, chunk));
            // Collect all entities in chunk, except online players: removing one disconnects it,
            // and the contract of Instance#unloadChunk is to leave them alone. A player whose
            // connection is already gone is not one of them: its removal is only queued for the next
            // tick, and deleting the partition below would cancel it for good, leaving a ghost in
            // the tracker. It is removed here like any other entity (REQ-M2-013).
            for (Entity entity : getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES)) {
                if (!(entity instanceof Player player) || !player.isOnline()) entities.add(entity);
            }
            // Clear cache
            this.chunks.remove(CoordConversion.chunkIndex(chunkX, chunkZ));
            tickets.unloaded(CoordConversion.chunkIndex(chunkX, chunkZ), chunk);
            chunk.unload();
            chunkLoader.unloadChunk(chunk);
            getEntityTracker().unregisterViewable(chunk.viewable);
            var dispatcher = process().dispatcher();
            dispatcher.deletePartition(chunk);
        }
        // unloadChunk may run on any thread; removal must own the entity's tick thread. That lock is
        // taken outside the instance monitor: a tick thread holds its own lock while entering
        // UNSAFE_setBlock, so taking them in the other order here would deadlock both.
        for (Entity entity : entities) {
            var acquirable = entity.acquirable();
            // No tick thread owns the entity yet, nothing can be racing with us
            if (acquirable.assignedThread() == null) entity.remove();
            else acquirable.sync(Entity::remove);
        }
    }

    /**
     * The automatic unload ({@code docs/DESIGN-M3.md} {@code API-M3-006}, internal task
     * {@code chunk.unload}): the chunks without a ticket for the grace period, the longest idle first,
     * at most {@value #MAX_UNLOADS_PER_TICK} a tick, unless an online player stands in one or it is not
     * {@link Chunk#readyToUnload() ready}. The ticket count is checked again under the ticket lock
     * right before a chunk goes; a ticket arriving after that no longer holds it, it stays on the
     * coordinates and protects the chunk once it loads again.
     * <p>
     * The idle chunks are read in batches, each twice as large as the one before: a tick that passes
     * over none reads {@value #MAX_UNLOADS_PER_TICK} of them however many are idle, and one that passes
     * over many (not ready, an online player in them, a throw) still reaches the chunks behind them. A
     * chunk passed over keeps its place among the idle chunks, so the next batch starts past as many
     * as were passed over.
     */
    @Override
    public void unloadIdleChunks() {
        final int grace = chunkUnloadGraceTicks();
        if (grace < 1) return;
        int unloaded = 0, passedOver = 0;
        for (int batch = MAX_UNLOADS_PER_TICK; unloaded < MAX_UNLOADS_PER_TICK; batch *= 2) {
            final LongList candidates = tickets.idleFor(grace, passedOver, batch);
            for (int i = 0; i < candidates.size() && unloaded < MAX_UNLOADS_PER_TICK; i++) {
                final long index = candidates.getLong(i);
                final int chunkX = CoordConversion.chunkIndexGetX(index);
                final int chunkZ = CoordConversion.chunkIndexGetZ(index);
                final Chunk chunk = getChunk(chunkX, chunkZ);
                // Not passed over: unloaded by now, it has left the idle chunks, like one with a ticket since below
                if (chunk == null) continue;
                try {
                    final boolean onlinePlayer = getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.PLAYERS)
                            .stream().anyMatch(Player::isOnline);
                    if (onlinePlayer || !chunk.readyToUnload()) {
                        passedOver++;
                        continue;
                    }
                    if (!tickets.stillIdle(index, chunk)) continue;
                    unloadChunk(chunk);
                    unloaded++;
                } catch (RuntimeException e) {
                    passedOver++;
                    process().exception().handleException(e);
                }
            }
            if (candidates.size() < batch) return;
        }
    }

    @Override
    public @Nullable Chunk getChunk(int chunkX, int chunkZ) {
        return chunks.get(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    @Override
    public CompletableFuture<Void> saveInstance() {
        final ChunkLoader chunkLoader = this.chunkLoader;
        return optionalAsync(chunkLoader.supportsParallelSaving(), () -> chunkLoader.saveInstance(this));
    }

    @Override
    public CompletableFuture<Void> saveChunkToStorage(Chunk chunk) {
        final ChunkLoader chunkLoader = this.chunkLoader;
        return optionalAsync(chunkLoader.supportsParallelSaving(), () -> chunkLoader.saveChunk(chunk));
    }

    @Override
    public CompletableFuture<Void> saveChunksToStorage() {
        final ChunkLoader chunkLoader = this.chunkLoader;
        return optionalAsync(chunkLoader.supportsParallelSaving(), () -> chunkLoader.saveChunks(getChunks()));
    }

    private CompletableFuture<Void> optionalAsync(boolean async, Runnable runnable) {
        if (!async) {
            runnable.run();
            return AsyncUtils.empty();
        }
        return CompletableFuture.runAsync(runnable, Thread::startVirtualThread).whenComplete((_, e) -> {
            if (e != null) process().exception().handleException(e);
        });
    }

    // Loaders must not force other chunks to load from within loadChunk: loaders
    // without parallel support run inside the loadingChunks computation, where a
    // reentrant load on this instance would violate the map's recursive update rules
    protected CompletableFuture<Chunk> retrieveChunk(int chunkX, int chunkZ) {
        final long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        final CompletableFuture<Chunk> future = loadingChunks.computeIfAbsent(index, _ -> {
            // A completed load may have cached the chunk and cleared its entry between the
            // caller's cache miss and this computation, re-check to avoid loading twice
            final Chunk cached = getChunk(chunkX, chunkZ);
            if (cached != null) return CompletableFuture.completedFuture(cached);
            final ChunkLoader loader = chunkLoader;
            final Supplier<@Nullable Chunk> loaderSupplier = () -> {
                var chunkLoading = EventsJFR.newChunkLoading(getUuid(), loader.getClass(), chunkX, chunkZ);
                chunkLoading.begin();
                final Chunk chunk = loader.loadChunk(this, chunkX, chunkZ);
                chunkLoading.end();
                if (chunk != null) chunkLoading.commit();
                return chunk;
            };
            final Function<@Nullable Chunk, Chunk> processChunk = loaded -> {
                Chunk chunk = loaded;
                if (chunk == null) {
                    // Loader couldn't load the chunk, generate it
                    var chunkGeneration = EventsJFR.newChunkGeneration(getUuid(), chunkX, chunkZ);
                    chunkGeneration.begin();
                    chunk = createChunk(chunkX, chunkZ);
                    chunk.onGenerate();
                    chunkGeneration.commit();
                }
                // TODO run in the instance thread?
                cacheChunk(chunk);
                applyPendingForks(chunk);
                chunk.onLoad();
                return chunk;
            };
            final CompletableFuture<Chunk> chain = loader.supportsParallelLoading()
                    // Load and process on a single virtual thread
                    ? CompletableFuture.supplyAsync(() -> processChunk.apply(loaderSupplier.get()), Thread::startVirtualThread)
                    // Loaders without parallel support load on the requesting thread, processing
                    // still hops off it to keep the chain from completing inside the mapping
                    : AsyncUtils.empty().thenApply(_ -> loaderSupplier.get())
                    .thenApplyAsync(processChunk, Thread::startVirtualThread);
            // The chain never completes inside the mapping, this callback is always async
            var _ = chain.whenComplete((chunk, e) -> {
                if (e != null) {
                    process().exception().handleException(e instanceof CompletionException ce ? ce.getCause() : e);
                } else {
                    process().eventHandler().call(new InstanceChunkLoadEvent(this, chunk));
                }
            });
            return chain;
        });
        return future.whenComplete((_, _) -> loadingChunks.remove(index, future));
    }

    Map<Long, List<GeneratorImpl.SectionModifierImpl>> generationForks = new ConcurrentHashMap<>();

    protected Chunk createChunk(int chunkX, int chunkZ) {
        final Chunk chunk = chunkSupplier.createChunk(this, chunkX, chunkZ);
        Objects.requireNonNull(chunk, "Chunks supplied by a ChunkSupplier cannot be null.");
        Generator generator = generator();
        if (generator == null || !chunk.shouldGenerate()) {
            // No chunk generator, execute the callback with the empty chunk
            processFork(chunk);
            return chunk;
        }
        generateChunk(chunk, generator);
        return chunk;
    }

    protected void generateChunk(Chunk chunk, Generator generator) {
        final int chunkX = chunk.getChunkX(), chunkZ = chunk.getChunkZ();
        GeneratorImpl.GenSection[] genSections = new GeneratorImpl.GenSection[chunk.getSections().size()];
        Arrays.setAll(genSections, i -> {
            Section section = chunk.getSections().get(i);
            return new GeneratorImpl.GenSection(section.blockPalette(), section.biomePalette());
        });
        var chunkUnit = GeneratorImpl.chunk(registries().biome(), genSections,
                chunk.getChunkX(), chunk.minSection, chunk.getChunkZ());
        try {
            // Generate block/biome palette
            generator.generate(chunkUnit);
            // Apply nbt/handler
            if (chunkUnit.modifier() instanceof GeneratorImpl.AreaModifierImpl chunkModifier) {
                for (var section : chunkModifier.sections()) {
                    if (section.modifier() instanceof GeneratorImpl.SectionModifierImpl sectionModifier) {
                        applyGenerationData(chunk, sectionModifier);
                    }
                }
            }
            // Register forks or apply locally
            for (var fork : chunkUnit.forks()) {
                var sections = ((GeneratorImpl.AreaModifierImpl) fork.modifier()).sections();
                for (var section : sections) {
                    if (section.modifier() instanceof GeneratorImpl.SectionModifierImpl sectionModifier) {
                        if (sectionModifier.genSection().blocks().count() == 0)
                            continue;
                        final Point start = section.absoluteStart();
                        final DimensionType dim = getCachedDimensionType();
                        if (start.blockY() < dim.minY() || start.blockY() >= dim.maxY()) {
                            // No chunk has such a section, applying it would abort the whole generation
                            LOGGER.warn("tried to fork a section outside the world bounds, should be within [{}, {}): {}",
                                    dim.minY(), dim.maxY(), start.blockY());
                            continue;
                        }
                        final Chunk forkChunk = start.chunkX() == chunkX && start.chunkZ() == chunkZ ? chunk : getChunkAt(start);
                        if (forkChunk != null) {
                            applyLoadedFork(forkChunk, sectionModifier);
                        } else {
                            final Chunk cached = keepFork(start, sectionModifier);
                            if (cached != null) applyLoadedFork(cached, sectionModifier);
                        }
                    }
                }
            }
            // Apply awaiting forks
            processFork(chunk);
        } catch (Throwable e) {
            process().exception().handleException(e);
        } finally {
            // End generation
            refreshLastBlockChangeTime();
        }
    }

    /**
     * Keeps a fork for a chunk the instance does not hold, to apply it once the chunk is generated or
     * cached. Checks again under the key of the chunk: one cached since {@code getChunkAt} may be past
     * both, then nothing is kept and the chunk is returned for the caller to apply the fork to.
     */
    private @Nullable Chunk keepFork(Point start, GeneratorImpl.SectionModifierImpl sectionModifier) {
        final Chunk[] cached = new Chunk[1];
        this.generationForks.compute(CoordConversion.chunkIndex(start), (_, sectionModifiers) -> {
            cached[0] = getChunkAt(start);
            if (cached[0] != null) return sectionModifiers;
            if (sectionModifiers == null) sectionModifiers = new ArrayList<>();
            sectionModifiers.add(sectionModifier);
            return sectionModifiers;
        });
        return cached[0];
    }

    /**
     * Applies the forks kept for a chunk the instance holds now: kept between its own processFork and
     * its caching, or for a chunk the loader read, which has no processFork. Every fork kept later sees
     * the chunk cached ({@link #keepFork}). Like a generation, a fork that throws goes to the exception
     * handler and the chunk loads anyway.
     */
    private void applyPendingForks(Chunk chunk) {
        final List<GeneratorImpl.SectionModifierImpl> pending =
                this.generationForks.remove(CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ()));
        if (pending == null) return;
        for (var sectionModifier : pending) {
            try {
                applyLoadedFork(chunk, sectionModifier);
            } catch (Throwable e) {
                process().exception().handleException(e);
            }
        }
    }

    private static void applyLoadedFork(Chunk forkChunk, GeneratorImpl.SectionModifierImpl sectionModifier) {
        applyFork(forkChunk, sectionModifier);
        // Refresh the cached chunk packet, then push the fork's changes as a per-section update instead of a chunk resend.
        forkChunk.invalidate();
        if (!forkChunk.getViewers().isEmpty()) // if we have viewers send the updates
            sendForkSectionUpdate(forkChunk, sectionModifier);
    }

    private void processFork(Chunk chunk) {
        this.generationForks.compute(CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ()), (_, sectionModifiers) -> {
            if (sectionModifiers != null) {
                for (var sectionModifier : sectionModifiers) {
                    applyFork(chunk, sectionModifier);
                }
            }
            return null;
        });
    }

    private static void applyFork(Chunk chunk, GeneratorImpl.SectionModifierImpl sectionModifier) {
        chunk.lockWriteLock();
        try {
            Section section = chunk.getSectionAt(sectionModifier.start().blockY());
            Palette currentBlocks = section.blockPalette();
            // -1 is necessary because forked units handle explicit changes by changing AIR 0 to 1
            sectionModifier.genSection().blocks().getAllPresent((x, y, z, value) -> currentBlocks.set(x, y, z, value - 1));
            applyGenerationData(chunk, sectionModifier);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    // Sends a fork's section changes to viewers as a single multi-block update instead of a full chunk resend.
    private static void sendForkSectionUpdate(Chunk forkChunk, GeneratorImpl.SectionModifierImpl sectionModifier) {
        final int section = CoordConversion.globalToChunk(sectionModifier.start().blockY());
        final LongList packed = new LongArrayList();
        sectionModifier.genSection().blocks().getAllPresent((x, y, z, value) ->
                packed.add(CoordConversion.encodeSectionBlockChange(CoordConversion.sectionBlockIndex(x, y, z), value - 1)));
        for (var entry : sectionModifier.genSection().specials().int2ObjectEntrySet()) {
            final Block block = entry.getValue();
            final BlockEntityType blockEntityType = block.blockEntityType();
            if (blockEntityType != null) {
                final int index = entry.getIntKey();
                final int x = CoordConversion.chunkBlockIndexGetX(index);
                final int y = CoordConversion.chunkBlockIndexGetY(index) + sectionModifier.start().blockY();
                final int z = CoordConversion.chunkBlockIndexGetZ(index);
                final Point blockPosition = new BlockVec(x + forkChunk.getChunkX() * 16, y, z + forkChunk.getChunkZ() * 16);
                final CompoundBinaryTag data = BlockUtils.extractClientNbt(block);
                forkChunk.sendPacketToViewers(new BlockEntityDataPacket(blockPosition, blockEntityType, data));
            }
        }
        if (packed.isEmpty()) return;
        forkChunk.sendPacketToViewers(new MultiBlockChangePacket(
                forkChunk.getChunkX(), section, forkChunk.getChunkZ(), packed.toLongArray()));
    }

    private static void applyGenerationData(Chunk chunk, GeneratorImpl.SectionModifierImpl section) {
        var cache = section.genSection().specials();
        if (cache.isEmpty()) return;
        final int height = section.start().blockY();
        chunk.lockWriteLock();
        try {
            Int2ObjectMaps.fastForEach(cache, blockEntry -> {
                final int index = blockEntry.getIntKey();
                final Block block = blockEntry.getValue();
                final int x = CoordConversion.chunkBlockIndexGetX(index);
                final int y = CoordConversion.chunkBlockIndexGetY(index) + height;
                final int z = CoordConversion.chunkBlockIndexGetZ(index);
                chunk.setBlock(x, y, z, block);
            });
        } finally {
            chunk.unlockWriteLock();
        }
    }

    @Override
    public void enableAutoChunkLoad(boolean enable) {
        this.autoChunkLoad = enable;
    }

    @Override
    public boolean hasEnabledAutoChunkLoad() {
        return autoChunkLoad;
    }

    @Override
    public boolean isInVoid(Point point) {
        // TODO: more customizable
        return point.y() < getCachedDimensionType().minY() - 64;
    }

    /**
     * Changes which type of {@link Chunk} implementation to use once one needs to be loaded.
     * <p>
     * Uses {@link DynamicChunk} by default.
     * <p>
     * WARNING: if you need to save this instance's chunks later,
     * the code needs to be predictable for {@link ChunkLoader#loadChunk(Instance, int, int)}
     * to create the correct type of {@link Chunk}. tl;dr: Need chunk save = no random type.
     *
     * @param chunkSupplier the new {@link ChunkSupplier} of this instance, chunks need to be non-null
     * @throws NullPointerException if {@code chunkSupplier} is null
     */
    @Override
    public void setChunkSupplier(ChunkSupplier chunkSupplier) {
        this.chunkSupplier = chunkSupplier;
    }

    /**
     * Gets the current {@link ChunkSupplier}.
     * <p>
     * You shouldn't use it to generate a new chunk, but as a way to view which one is currently in use.
     *
     * @return the current {@link ChunkSupplier}
     */
    @Override
    public ChunkSupplier getChunkSupplier() {
        return chunkSupplier;
    }

    /**
     * Copies all the chunks of this instance and create a new instance container with all of them.
     * <p>
     * Chunks are copied with {@link Chunk#copy(Instance, int, int)},
     * {@link UUID} is randomized and {@link DimensionType} is passed over.
     * The copy also inherits the chunk supplier, the {@link Generator} and the dimension name,
     * so chunks loaded afterwards match the copied ones. Its {@link ChunkLoader} is left at the
     * default, a copy must not write into the storage of its source.
     * <p>
     * <b>A copy costs as much memory as its source.</b> Every section is cloned, palettes and light
     * data included, so copying one instance per game of a big world multiplies the resident heap
     * by the number of copies. Generating the world again from the same generator is usually
     * cheaper than copying it.
     *
     * @return an {@link InstanceContainer} with the exact same chunks as 'this'
     * @see #getSrcInstance() to retrieve the "creation source" of the copied instance
     */
    public synchronized InstanceContainer copy() {
        InstanceContainer copiedInstance = new InstanceContainer(process(), UUID.randomUUID(),
                getDimensionType(), null, Key.key(getDimensionName()));
        copiedInstance.srcInstance = this;
        copiedInstance.tagHandler = this.tagHandler.copy();
        copiedInstance.lastBlockChangeTime = this.lastBlockChangeTime;
        copiedInstance.setChunkSupplier(this.chunkSupplier);
        copiedInstance.setGenerator(this.generator);
        for (Chunk chunk : chunks.values()) {
            final int chunkX = chunk.getChunkX();
            final int chunkZ = chunk.getChunkZ();
            final Chunk copiedChunk;
            chunk.lockReadLock();
            try {
                copiedChunk = chunk.copy(copiedInstance, chunkX, chunkZ);
            } finally {
                chunk.unlockReadLock();
            }
            copiedInstance.cacheChunk(copiedChunk);
            // Chunks are handed over already loaded, a LightingChunk stays unusable without it
            copiedChunk.onLoad();
        }
        return copiedInstance;
    }

    /**
     * Gets the instance from which this one has been copied.
     * <p>
     * Only present if this instance has been created with {@link InstanceContainer#copy()}.
     *
     * @return the instance source, null if not created by a copy
     * @see #copy() to create a copy of this instance with 'this' as the source
     */
    public @Nullable InstanceContainer getSrcInstance() {
        return srcInstance;
    }

    /**
     * Gets the last time at which a block changed.
     *
     * @return the time at which the last block changed in nanoseconds. Only use this to calculate delta times
     */
    public long getLastBlockChangeTime() {
        return lastBlockChangeTime;
    }

    /**
     * Signals the instance that a block changed.
     * <p>
     * Useful if you change blocks values directly using a {@link Chunk} object.
     */
    public void refreshLastBlockChangeTime() {
        this.lastBlockChangeTime = System.nanoTime();
    }

    @Override
    public @Nullable Generator generator() {
        return generator;
    }

    @Override
    public void setGenerator(@Nullable Generator generator) {
        this.generator = generator;
    }

    @ApiStatus.Experimental
    @Override
    public CompletableFuture<Void> generateChunk(int chunkX, int chunkZ, Generator generator) {
        return loadChunk(chunkX, chunkZ).thenAcceptAsync(chunk -> {
            chunk.lockWriteLock();
            try {
                generateChunk(chunk, generator);
                chunk.invalidate();
            } finally {
                chunk.unlockWriteLock();
            }
            chunk.sendChunk();
        }, Thread::startVirtualThread).whenComplete((_, e) -> {
            if (e != null) process().exception().handleException(e);
        });
    }

    /**
     * Gets all the instance chunks.
     *
     * @return the chunks of this instance
     */
    @Override
    public Collection<Chunk> getChunks() {
        return chunks.values();
    }

    /**
     * Gets the {@link ChunkLoader} of this instance.
     *
     * @return the {@link ChunkLoader} of this instance
     */
    public ChunkLoader getChunkLoader() {
        return chunkLoader;
    }

    /**
     * Changes the {@link ChunkLoader} of this instance (to change how chunks are retrieved when not already loaded).
     *
     * <p>{@link ChunkLoader#noop()} can be used to do nothing.</p>
     *
     * @param chunkLoader the new {@link ChunkLoader}
     */
    public void setChunkLoader(ChunkLoader chunkLoader) {
        this.chunkLoader = Objects.requireNonNull(chunkLoader, "Chunk loader cannot be null");
    }

    @Override
    public void tick(long time) {
        // Time/world border
        super.tick(time);
        // Clear block change map
        synchronized (this) {
            this.currentlyChangingBlocks.clear();
        }
    }

    /**
     * Has this block already changed since last update?
     * Prevents StackOverflow with blocks trying to modify their position in onDestroy or onPlace.
     *
     * @param blockPosition the block position
     * @param block         the block
     * @return true if the block changed since the last update
     */
    private boolean isAlreadyChanged(BlockVec blockPosition, Block block) {
        final Block changedBlock = currentlyChangingBlocks.get(blockPosition);
        return Objects.equals(changedBlock, block);
    }

    /**
     * Executed when a block is modified, this is used to modify the states of neighbours blocks.
     * <p>
     * For example, this can be used for redstone wires which need an understanding of its neighborhoods to take the right shape.
     *
     * @param blockPosition the position of the modified block
     */
    private void executeNeighboursBlockPlacementRule(Point blockPosition, int updateDistance) {
        ChunkCache cache = new ChunkCache(this, null, null);
        for (var updateFace : BLOCK_UPDATE_FACES) {
            var direction = updateFace.toDirection();
            final int neighborX = blockPosition.blockX() + direction.normalX();
            final int neighborY = blockPosition.blockY() + direction.normalY();
            final int neighborZ = blockPosition.blockZ() + direction.normalZ();
            if (neighborY < getCachedDimensionType().minY() || neighborY >= getCachedDimensionType().maxY())
                continue;
            final Block neighborBlock = cache.getBlock(neighborX, neighborY, neighborZ, Condition.NONE);
            if (neighborBlock == null || neighborBlock.air())
                continue;
            final BlockPlacementRule neighborBlockPlacementRule = process().block().getBlockPlacementRule(neighborBlock);
            if (neighborBlockPlacementRule == null || updateDistance >= neighborBlockPlacementRule.maxUpdateDistance())
                continue;

            final Vec neighborPosition = new Vec(neighborX, neighborY, neighborZ);
            final Block newNeighborBlock = neighborBlockPlacementRule.blockUpdate(new BlockPlacementRule.UpdateState(
                    this,
                    neighborPosition,
                    neighborBlock,
                    updateFace.getOppositeFace()
            ));
            if (neighborBlock != newNeighborBlock) {
                final Chunk chunk = getChunkAt(neighborPosition);
                if (!isLoaded(chunk)) continue;
                UNSAFE_setBlock(chunk, neighborPosition.blockX(), neighborPosition.blockY(), neighborPosition.blockZ(), newNeighborBlock,
                        null, null, true, updateDistance + 1);
            }
        }
    }

    private void cacheChunk(Chunk chunk) {
        final long index = CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ());
        // Only a registered instance may hand chunks to the dispatcher: partitions are released on
        // unregistration, so an unregistered instance would leak them. InstanceManager#registerInstance
        // creates the partitions of the chunks cached before registration.
        final var dispatcher = process().dispatcher();
        final boolean registered = isRegistered();
        // Registered: the partition is signalled before any thread sees the chunk, so an entity moving
        // into it is updated after the partition exists, not dropped from every partition before it
        if (registered) dispatcher.createPartition(chunk);
        beforeCaching(chunk);
        this.chunks.put(index, chunk);
        tickets.loaded(index, chunk);
        // Registered meanwhile: registerInstance may have scanned the chunks before this one was in them; the
        // registered flag is volatile, so that scan or this read sees the other. Duplicate partitions are ignored.
        if (!registered && isRegistered()) dispatcher.createPartition(chunk);
    }

    /**
     * Runs on the thread caching {@code chunk}, once its partition is signalled and before the instance
     * holds it, for a test to stop there. Does nothing.
     *
     * @param chunk the chunk being cached
     */
    void beforeCaching(Chunk chunk) {
    }
}
