package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.thread.Tickable;
import io.github.bearisbug.synaxis.Viewable;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.tag.TagHandler;
import io.github.bearisbug.synaxis.tag.Taggable;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A column of 16×16 blocks of an {@link Instance}, in {@link #getMaxSection()} - {@link #getMinSection()}
 * sections of 16 blocks height, as many as the {@code minY} and {@code height} of its dimension make.
 * Should contain all the blocks located at those positions and manage their tick updates.
 * <p>
 * Each chunk has one read-write lock. The reads ({@code getBlock}, {@code getBiome}, the content of its
 * sections, {@link #copy}) hold the {@link #lockReadLock() read-lock}; the changes ({@link #setBlock},
 * {@code setBiome}, {@link #reset()}, {@link #loadHeightmapsFromNBT}) hold the
 * {@link #lockWriteLock() write-lock} and throw {@link IllegalStateException} without it. The read-lock
 * cannot be upgraded to the write-lock. The methods of {@link Instance} of the same names take the lock
 * themselves, and {@link Instance#setBlock} also tells the viewers and runs the placement rules: plugins
 * use those. {@link #setReadOnly(boolean)} needs no lock, and {@link #tick(long)} is called without one
 * and takes the locks it needs.
 * <p>
 * You can create your own implementation of this class by extending it
 * and create the objects in {@link InstanceContainer#setChunkSupplier(ChunkSupplier)}.
 * <p>
 * You generally want to avoid storing references of this object as this could lead to a huge memory leak,
 * you should store the chunk coordinates instead.
 */
public abstract class Chunk implements Block.Getter, Block.Setter, Biome.Getter, Biome.Setter, Viewable, Tickable, Taggable {
    public static final int CHUNK_SIZE_X = 16;
    public static final int CHUNK_SIZE_Z = 16;
    public static final int CHUNK_SECTION_SIZE = 16;

    private final UUID identifier;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    protected Instance instance;
    protected final int chunkX, chunkZ;
    protected final int minSection, maxSection;

    // Options
    private final boolean shouldGenerate;
    // Set and read from any thread without the chunk lock
    private volatile boolean readOnly;

    protected volatile boolean loaded = true;
    final Viewable viewable;

    // Data
    private final TagHandler tagHandler = TagHandler.newHandler();

    public Chunk(Instance instance, int chunkX, int chunkZ, boolean shouldGenerate) {
        this.identifier = UUID.randomUUID();
        this.instance = instance;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.shouldGenerate = shouldGenerate;
        final DimensionType instanceDim = instance.getCachedDimensionType();
        this.minSection = instanceDim.minY() / CHUNK_SECTION_SIZE;
        this.maxSection = (instanceDim.minY() + instanceDim.height()) / CHUNK_SECTION_SIZE;
        this.viewable = instance.getEntityTracker().viewable(chunkX, chunkZ);
    }

    /**
     * Sets a block at a position.
     * <p>
     * This is used when the previous block has to be destroyed/replaced, meaning that it clears the previous data and update method.
     * <p>
     * The caller holds the {@link #lockWriteLock() write-lock}. Unlike {@link Instance#setBlock(int, int, int, Block)}
     * (or any similar instance methods) this method sends no block change, ignores {@link #isReadOnly()}, calls
     * no event and runs no placement rule. {@link DynamicChunk} ignores a y outside the world, with a warning.
     *
     * @param x     the block X
     * @param y     the block Y
     * @param z     the block Z
     * @param block the block to place
     * @throws IllegalStateException if the calling thread does not hold the write-lock of this chunk
     */
    @Override
    public void setBlock(int x, int y, int z, Block block) {
        assertWriteLock();
        setBlock(x, y, z, block, null, null);
    }

    protected abstract void setBlock(int x, int y, int z, Block block,
                                     @Nullable BlockHandler.Placement placement,
                                     @Nullable BlockHandler.Destroy destroy);

    public abstract List<Section> getSections();

    public abstract Section getSection(int section);

    public abstract Heightmap motionBlockingHeightmap();

    public abstract Heightmap worldSurfaceHeightmap();

    public abstract void loadHeightmapsFromNBT(CompoundBinaryTag heightmaps);

    public Section getSectionAt(int blockY) {
        return getSection(CoordConversion.globalToChunk(blockY));
    }

    /**
     * Executes a chunk tick.
     * <p>
     * Should be used to update all the blocks in the chunk.
     * <p>
     * Called by a tick thread without the chunk lock: an implementation takes the locks it needs.
     *
     * @param time the time of the update in milliseconds
     */
    @Override
    public abstract void tick(long time);

    /**
     * Sends the chunk data to {@code player}.
     *
     * @param player the player
     */
    public void sendChunk(Player player) {
        player.sendChunk(this);
    }

    public void sendChunk() {
        getViewers().forEach(this::sendChunk);
    }

    @ApiStatus.Internal
    public abstract SendablePacket getFullDataPacket();

    /**
     * Creates a copy of this chunk, including blocks state id, custom block id, biomes, update data.
     * <p>
     * The chunk position (X/Z) can be modified using the given arguments.
     *
     * @param instance the chunk owner
     * @param chunkX   the chunk X of the copy
     * @param chunkZ   the chunk Z of the copy
     * @return a copy of this chunk with a potentially new instance and position
     */
    public abstract Chunk copy(Instance instance, int chunkX, int chunkZ);

    /**
     * Resets the chunk, this means clearing all the data making it empty: blocks, biomes and block
     * entities, and with them the block ticks.
     * <p>
     * Calls no {@link BlockHandler#onDestroy}, keeps the light and tells no viewer: online players see
     * the result once {@link #sendChunk()} sends it. The caller holds the {@link #lockWriteLock() write-lock}.
     */
    public abstract void reset();

    /**
     * Gets the unique identifier of this chunk.
     * <p>
     * WARNING: this UUID is not persistent but randomized once the object is instantiated.
     *
     * @return the chunk identifier
     */
    public UUID getIdentifier() {
        return identifier;
    }

    /**
     * Gets the instance where this chunk is stored
     *
     * @return the linked instance
     */
    public Instance getInstance() {
        return instance;
    }

    /**
     * Gets the chunk X.
     *
     * @return the chunk X
     */
    public int getChunkX() {
        return chunkX;
    }

    /**
     * Gets the chunk Z.
     *
     * @return the chunk Z
     */
    public int getChunkZ() {
        return chunkZ;
    }

    /**
     * Gets the lowest (inclusive) section Y available in this chunk
     *
     * @return the lowest (inclusive) section Y available in this chunk
     */
    public int getMinSection() {
        return minSection;
    }

    /**
     * Gets the highest (exclusive) section Y available in this chunk
     *
     * @return the highest (exclusive) section Y available in this chunk
     */
    public int getMaxSection() {
        return maxSection;
    }

    /**
     * Gets the world position of this chunk.
     *
     * @return the position of this chunk
     */
    public Point toPosition() {
        return new Vec(CHUNK_SIZE_X * getChunkX(), 0, CHUNK_SIZE_Z * getChunkZ());
    }

    /**
     * Gets if this chunk will or had been loaded with a {@link Generator}.
     * <p>
     * If false, the chunk will be entirely empty when loaded.
     *
     * @return true if this chunk is affected by a {@link Generator}
     */
    public boolean shouldGenerate() {
        return shouldGenerate;
    }

    /**
     * Gets if this chunk is read-only. Any thread may call it, without the chunk lock.
     * <p>
     * A read-only chunk refuses {@link Instance#setBlock}, {@link Instance#placeBlock} and
     * {@link Instance#breakBlock}, and with them the blocks players place and break. It does not refuse
     * {@link Instance#setBiome} or a direct {@link #setBlock}, and does not affect {@link ChunkLoader} and
     * {@link Generator}. A subclass may add conditions of its own here, which {@link #setReadOnly(boolean)}
     * does not lift.
     *
     * @return true if the chunk is read-only
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Changes the read state of the chunk, see {@link #isReadOnly()}. Any thread may call it, without the
     * chunk lock: the flag is no content of the chunk, and the next read on any thread sees it.
     *
     * @param readOnly true to make the chunk read-only, false otherwise
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * Used to verify if the chunk should still be kept in memory.
     *
     * @return true if the chunk is loaded
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Called when the chunk has been successfully loaded.
     */
    protected void onLoad() {
    }

    /**
     * Called when the chunk generator has finished generating the chunk.
     */
    public void onGenerate() {
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + chunkX + ":" + chunkZ + "]";
    }

    /**
     * Not supported, see {@link #getViewers()}.
     *
     * @throws UnsupportedOperationException always: a chunk's viewers are the players whose chunk ring
     *                                       contains it ({@link Player#isChunkInRing}), set by their position
     *                                       and view distance; there is no manual viewer list
     */
    @Override
    public boolean addViewer(Player player) {
        return viewable.addViewer(player);
    }

    /**
     * Not supported, see {@link #getViewers()}.
     *
     * @throws UnsupportedOperationException always: a chunk's viewers are the players whose chunk ring
     *                                       contains it ({@link Player#isChunkInRing}), set by their position
     *                                       and view distance; there is no manual viewer list
     */
    @Override
    public boolean removeViewer(Player player) {
        return viewable.removeViewer(player);
    }

    /**
     * The players whose chunk ring contains this chunk ({@link Player#isChunkInRing}). Each call, and each
     * {@code size}, {@code isEmpty}, {@code contains} and iteration of the set it returns, scans the players
     * of the instance again: no snapshot, two of them may differ. Take one first for a consistent result,
     * {@code List.copyOf(getViewers())}.
     */
    @Override
    public Set<? extends Player> getViewers() {
        return viewable.getViewers();
    }

    @Override
    public TagHandler tagHandler() {
        return tagHandler;
    }

    /**
     * Sets the chunk as "unloaded".
     */
    protected void unload() {
        this.loaded = false;
    }

    /**
     * Asked by the automatic unload of an instance before it unloads this chunk, on the tick
     * scheduling thread ({@code docs/DESIGN-M3.md} API-M3-006). A subclass returns false to keep the
     * chunk one more round, for instance while it still has changes to write out. The explicit
     * {@link Instance#unloadChunk(Chunk)} does not ask.
     *
     * @return whether the chunk may be unloaded now
     */
    protected boolean readyToUnload() {
        return true;
    }

    /**
     * Invalidate the chunk caches
     */
    public abstract void invalidate();

    /**
     * Checks that the calling thread holds the write-lock, with or without assertions enabled: a change
     * without it races the serialization of the chunk packet under the read-lock.
     *
     * @throws IllegalStateException if the calling thread does not hold the write-lock
     */
    @ApiStatus.Internal
    protected final void assertWriteLock() {
        if (!holdsWriteLock())
            throw new IllegalStateException("Chunk " + chunkX + "," + chunkZ + " changed without its write-lock: "
                    + "use Instance#setBlock or Instance#setBiome, or hold lockWriteLock() around the change");
    }

    @ApiStatus.Internal
    protected final void assertReadLock() {
        assert holdsReadLock() : "Not holding read-lock for chunk " + chunkX + "," + chunkZ;
    }

    /**
     * Takes the write-lock of this chunk, waiting as long as it takes. Reentrant, and its holder may
     * take the read-lock too. The read-lock cannot be upgraded: a thread holding it and not the
     * write-lock would wait for itself forever, and gets an exception instead. The instance methods
     * changing a chunk ({@link Instance#setBlock}, {@link Instance#setBiome}) take it: a check followed
     * by a change takes the write-lock from the start.
     *
     * @throws IllegalStateException if the calling thread holds the read-lock and not the write-lock
     */
    @ApiStatus.Experimental
    public final void lockWriteLock() {
        if (lock.getReadHoldCount() > 0 && !lock.isWriteLockedByCurrentThread())
            throw new IllegalStateException("Chunk " + chunkX + "," + chunkZ + ": this thread holds the read-lock, "
                    + "which cannot be upgraded to the write-lock; take the write-lock from the start");
        lock.writeLock().lock();
    }

    /**
     * Takes the write-lock {@link #lockWriteLock()} takes, waiting at most {@code timeout} for it.
     * Reentrant like that one. An interrupt ends the wait: the method returns false and the
     * interrupt stays set. A thread holding the read-lock and not the write-lock waits out the whole
     * timeout and gets false, the read-lock cannot be upgraded (with assertions enabled it fails an
     * assertion right away).
     *
     * @param timeout how long to wait at most, zero or negative to only try once
     * @return whether the calling thread holds the write-lock now; it has to unlock it then
     */
    @ApiStatus.Experimental
    public final boolean tryLockWriteLock(Duration timeout) {
        assert holdsWriteLock() || lock.getReadHoldCount() == 0 : "Cannot acquire write-lock while holding read-lock for chunk " + chunkX + "," + chunkZ;
        try {
            return lock.writeLock().tryLock(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @ApiStatus.Experimental
    public final void unlockWriteLock() {
        lock.writeLock().unlock();
    }

    /**
     * Takes the read-lock of this chunk, shared with other readers. It cannot be upgraded: while the
     * calling thread holds it and not the write-lock, {@link #lockWriteLock()} throws, and so do the
     * instance methods taking it ({@link Instance#setBlock}, {@link Instance#setBiome}).
     */
    @ApiStatus.Experimental
    public final void lockReadLock() {
        lock.readLock().lock();
    }

    @ApiStatus.Experimental
    public final void unlockReadLock() {
        lock.readLock().unlock();
    }

    /**
     * {@return whether the calling thread holds the chunk write-lock}
     */
    @ApiStatus.Experimental
    public final boolean holdsWriteLock() {
        return lock.isWriteLockedByCurrentThread();
    }

    /**
     * {@return whether the calling thread holds the chunk read-lock}
     */
    @ApiStatus.Experimental
    public final boolean holdsReadLock() {
        return holdsWriteLock() || lock.getReadHoldCount() > 0;
    }
}