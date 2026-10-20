package io.github.bearisbug.synaxis.instance.heightmap;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.utils.MathUtils;

import static io.github.bearisbug.synaxis.coordinate.CoordConversion.globalToChunk;
import static io.github.bearisbug.synaxis.coordinate.CoordConversion.globalToSectionRelative;
import static io.github.bearisbug.synaxis.instance.Chunk.CHUNK_SIZE_X;
import static io.github.bearisbug.synaxis.instance.Chunk.CHUNK_SIZE_Z;

/**
 * The highest block of each column of a chunk that {@link #checkBlock(Block)} counts.
 * <p>
 * {@link #getHeight(int, int)} can be called on any thread: a heightmap that needs a refresh is computed again
 * under the read-lock of the chunk, and a reader arriving during that computation waits for it instead of reading
 * the columns it has not reached yet. A caller holding the read-lock reads heights that match the blocks. The
 * chunk implementation keeps the heightmap with {@link #refresh(int, int, int, Block)} and
 * {@link #loadFrom(long[])} under the write-lock of the chunk. {@link #refresh(int)} takes the read-lock itself,
 * which keeps the blocks from changing while it computes, then a lock of the heightmap, which one computation holds
 * at a time; it can be called under the write-lock too. {@link #refresh(int, int, int)} computes one column, under
 * either lock. {@link #invalidate()} marks the heights stale, so that the next read computes them again; called
 * during a computation, it waits for that computation to end.
 */
public abstract class Heightmap {
    public enum Type {
        WORLD_SURFACE_WG,
        WORLD_SURFACE,
        OCEAN_FLOOR_WG,
        OCEAN_FLOOR,
        MOTION_BLOCKING,
        MOTION_BLOCKING_NO_LEAVES;

        public static final NetworkBuffer.Type<Type> NETWORK_TYPE = NetworkBuffer.Enum(Type.class);
    }

    private final short[] heights = new short[CHUNK_SIZE_X * CHUNK_SIZE_Z];
    private final Chunk chunk;
    private final int minHeight;
    // Held while the heights are computed or loaded; no lock of the chunk is taken while holding it
    private final Object computing = new Object();
    // invalidate() moves version on; the heights match the blocks while computedVersion equals it
    private volatile int version;
    private volatile int computedVersion = -1;

    public Heightmap(Chunk chunk) {
        this.chunk = chunk;
        this.minHeight = chunk.getInstance().getCachedDimensionType().minY() - 1;
    }

    public abstract Type type();

    protected abstract boolean checkBlock(Block block);

    /**
     * Updates the column of a block set at chunk-local x and z and world y. Called by the chunk on
     * {@code setBlock}; a plugin calling it rewrites the heightmap.
     *
     * @param x     the x of the column in the chunk, in [0, 16)
     * @param y     the world y of the block
     * @param z     the z of the column in the chunk, in [0, 16)
     * @param block the block set
     */
    public void refresh(int x, int y, int z, Block block) {
        final int height = getHeight(x, z);
        if (checkBlock(block)) {
            if (height < y) setHeightY(x, z, y);
        } else if (y == height) {
            refresh(x, z, y - 1);
        }
    }

    /**
     * Computes every column from {@code startY} down, only when this heightmap was never computed or
     * was {@link #invalidate() invalidated} since; otherwise does nothing. Called by the engine on a read
     * of a heightmap that needs it; a plugin calling it rewrites the heightmap.
     *
     * @param startY the block Y to look down from, {@link #getHighestBlockSection(Chunk)} covers the chunk
     */
    public void refresh(int startY) {
        computeIfStale(false, startY);
    }

    /**
     * The engine's reads look down from {@link #getHighestBlockSection(Chunk)}, found under the lock: found before
     * it, a block written meanwhile above the highest section would be missed by a computation that counts as done.
     */
    private void computeIfStale(boolean fromTop, int startY) {
        if (!needsRefresh()) return;
        chunk.lockReadLock();
        try {
            synchronized (computing) {
                // A reader that waited here finds the heights computed by the thread it waited for
                final int started = version;
                if (computedVersion == started) return;
                final int from = fromTop ? getHighestBlockSection(chunk) : startY;
                for (int x = 0; x < CHUNK_SIZE_X; x++) {
                    for (int z = 0; z < CHUNK_SIZE_Z; z++) {
                        refresh(x, z, from);
                    }
                }
                computedVersion = started;
            }
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * Marks every column stale, for blocks changed without {@link #refresh(int, int, int, Block)}: the
     * next read computes them again.
     */
    public void invalidate() {
        synchronized (computing) {
            version++;
        }
    }

    private boolean needsRefresh() {
        return computedVersion != version;
    }

    /**
     * Computes one column from {@code startY} down. Called by the engine when it computes the heightmap
     * again and when the top block of a column is replaced by one it does not count; a plugin calling it
     * rewrites the heightmap.
     *
     * @param x      the x of the column in the chunk, in [0, 16)
     * @param z      the z of the column in the chunk, in [0, 16)
     * @param startY the world y to look down from
     */
    public void refresh(int x, int z, int startY) {
        final int localX = globalToSectionRelative(x);
        final int localZ = globalToSectionRelative(z);

        int foundHeight = minHeight;
        int currentY = startY;
        while (currentY > minHeight) {
            final int sectionY = globalToChunk(currentY);
            if (sectionY < chunk.getMinSection() || sectionY >= chunk.getMaxSection()) {
                currentY = (sectionY << 4) - 1; // Move to the bottom of the previous section
                continue;
            }

            final Palette blockPalette = chunk.getSection(sectionY).blockPalette();
            final int localHeight = blockPalette.height(localX, localZ, (_, _, _, value) -> {
                if (value == 0) return false;
                final Block block = Block.fromStateId(value);
                return block != null && checkBlock(block);
            });
            if (localHeight >= 0) {
                // Found a matching block, convert local Y back to world Y
                foundHeight = (sectionY << 4) + localHeight;
                break;
            }

            // No matching block found in this section, move to the section below
            currentY = (sectionY << 4) - 1;
        }
        setHeightY(x, z, foundHeight);
    }

    public long[] getNBT() {
        computeIfStale(true, 0);
        final int dimensionHeight = chunk.getInstance().getCachedDimensionType().height();
        final int bitsForHeight = MathUtils.bitsToRepresent(dimensionHeight);
        return encode(heights, bitsForHeight);
    }

    /**
     * Takes the heights saved with the chunk: reads return them, without computing the heightmap, until the
     * next {@link #invalidate()}. Called only by {@link Chunk#loadHeightmapsFromNBT} of {@code DynamicChunk},
     * under the write-lock of the chunk; no chunk loader of the engine calls that method, a loader or a plugin
     * may. An array too short for the height of the dimension, saved with another one, is ignored: a heightmap
     * never computed, or invalidated since, is computed on the next read, and one already computed keeps its heights.
     *
     * @param data the heights packed as {@link #getNBT()} writes them
     */
    public void loadFrom(long[] data) {
        final int dimensionHeight = chunk.getInstance().getCachedDimensionType().height();
        final int bitsPerEntry = MathUtils.bitsToRepresent(dimensionHeight);

        final int entriesPerLong = 64 / bitsPerEntry;

        // ceil(HeightsCount / entriesPerLong), a shorter array comes from a world saved with a
        // different dimension height: keep the heights as they are rather than read past it
        if (data.length < (heights.length + entriesPerLong - 1) / entriesPerLong) return;

        final int maxPossibleIndexInContainer = entriesPerLong - 1;
        final int entryMask = (1 << bitsPerEntry) - 1;

        synchronized (computing) {
            int containerIndex = 0;
            for (int i = 0; i < heights.length; i++) {
                final int indexInContainer = i % entriesPerLong;
                heights[i] = (short) ((int) (data[containerIndex] >> (indexInContainer * bitsPerEntry)) & entryMask);
                if (indexInContainer == maxPossibleIndexInContainer) containerIndex++;
            }
            computedVersion = version;
        }
    }

    /**
     * The world y of the highest block of a column that this heightmap counts.
     *
     * @param x the x of the column in the chunk, in [0, 16)
     * @param z the z of the column in the chunk, in [0, 16)
     * @return the world y of the highest matching block, {@code minY - 1} of the dimension for a column without one
     * @throws IllegalArgumentException if x or z is outside [0, 16), a world coordinate for instance
     */
    public int getHeight(int x, int z) {
        if (((x | z) >>> 4) != 0)
            throw new IllegalArgumentException("heightmap column must be chunk-local [0,16): " + x + ", " + z);
        computeIfStale(true, 0);
        return heights[z << 4 | x] + minHeight;
    }

    private void setHeightY(int x, int z, int height) {
        heights[z << 4 | x] = (short) (height - minHeight);
    }

    /**
     * The block y just above the highest section of the chunk with a block in its palette, the y to look
     * down from: {@code maxY} of the dimension when the top section has one, {@code minY} when no section
     * has. A block y, not a section index.
     *
     * @param chunk the chunk
     * @return the block y above the highest non-empty section
     */
    public static int getHighestBlockSection(Chunk chunk) {
        int y = chunk.getInstance().getCachedDimensionType().maxY();
        final int sectionsCount = chunk.getMaxSection() - chunk.getMinSection();
        for (int i = 0; i < sectionsCount; i++) {
            final int sectionY = chunk.getMaxSection() - i - 1;
            final Palette blockPalette = chunk.getSection(sectionY).blockPalette();
            if (blockPalette.count() != 0) break;
            y -= 16;
        }
        return y;
    }

    /**
     * Creates compressed longs array from uncompressed heights array.
     *
     * @param heights      array of heights. Note that for this method it doesn't
     *                     matter what size this array will be.
     *                     But to get correct heights, array must be 256 elements
     *                     long, and at index `i` must be height of (z=i/16,
     *                     x=i%16).
     * @param bitsPerEntry bits that each entry from height will take in `long`
     *                     container.
     * @return array of encoded heights.
     */
    public static long[] encode(short[] heights, int bitsPerEntry) {
        final int entriesPerLong = 64 / bitsPerEntry;
        // ceil(HeightsCount / entriesPerLong)
        final int len = (heights.length + entriesPerLong - 1) / entriesPerLong;

        final int maxPossibleIndexInContainer = entriesPerLong - 1;
        final int entryMask = (1 << bitsPerEntry) - 1;

        long[] data = new long[len];
        int containerIndex = 0;
        for (int i = 0; i < heights.length; i++) {
            final int indexInContainer = i % entriesPerLong;
            final int entry = heights[i];
            data[containerIndex] |= ((long) (entry & entryMask)) << (indexInContainer * bitsPerEntry);
            if (indexInContainer == maxPossibleIndexInContainer) containerIndex++;
        }
        return data;
    }
}
