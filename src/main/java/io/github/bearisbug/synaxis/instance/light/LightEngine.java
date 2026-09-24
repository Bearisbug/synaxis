package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Direction;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;
import java.util.Objects;

/**
 * Sky and block light computed from a {@link WorldView} alone: no instance, no chunk, no lock. A call reads the
 * view, keeps everything it builds on the calling thread and returns a new {@link LightResult}, so two threads
 * may compute over the same view at once. The rules are those of {@link LightBlocks}: light entering a block loses
 * {@link LightBlocks#opacity}, faces are {@link LightBlocks#occludes}, and every block from the top of the world
 * down to the first {@link LightBlocks#skyEdgeOccluded} edge of its column is a sky source of level 15.
 */
@ApiStatus.Experimental
public final class LightEngine {
    /**
     * Raised by every change that changes the output. {@code verified-light-versions.txt} lists the versions that
     * passed the vanilla parity test, and {@code LightEngineVersionTest} fails when the output changes without it.
     */
    public static final int VERSION = 1;

    /** Sections one computation holds at most, ring included: a cell is {@code section << 12 | index}, an int. */
    public static final int MAX_SECTIONS = 1 << 19;

    private static final int AIR = Block.AIR.stateId();
    private static final byte[] OPACITY;
    private static final byte[] EMISSION;
    private static final boolean[] SHAPED;
    /** {@link LightBlocks#skyEdgeOccluded} with air on top, the edge almost every column meets first. */
    private static final boolean[] SKY_EDGE_UNDER_AIR;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final BlockFace[] FACES = new BlockFace[DIRECTIONS.length];
    /** A section every cell of which is a sky source. Never written: no propagated level exceeds 14. */
    private static final byte[] SKY_SOURCES = new byte[4096];

    static {
        final int states = Block.statesCount();
        OPACITY = new byte[states];
        EMISSION = new byte[states];
        SHAPED = new boolean[states];
        SKY_EDGE_UNDER_AIR = new boolean[states];
        final Object noFaces = LightBlocks.shape(Block.AIR);
        for (int stateId = 0; stateId < states; stateId++) {
            final Block block = Objects.requireNonNull(Block.fromStateId(stateId));
            OPACITY[stateId] = (byte) LightBlocks.opacity(block);
            EMISSION[stateId] = (byte) block.lightEmission();
            SHAPED[stateId] = LightBlocks.shape(block) != noFaces;
            SKY_EDGE_UNDER_AIR[stateId] = LightBlocks.skyEdgeOccluded(Block.AIR, block);
        }
        for (int i = 0; i < DIRECTIONS.length; i++) FACES[i] = BlockFace.fromDirection(DIRECTIONS[i]);
        Arrays.fill(SKY_SOURCES, (byte) 15);
    }

    private LightEngine() {
    }

    /**
     * Light of chunks {@code chunkMinX..chunkMaxX} by {@code chunkMinZ..chunkMaxZ}. The view must hold every
     * chunk it has within one chunk of that rectangle; light from further away cannot reach it. A chunk the view
     * does not have neither emits nor passes light. The rectangle with that ring holds at most
     * {@value #MAX_SECTIONS} sections, 147 x 147 chunks at a height of 384.
     */
    public static LightResult computeArea(WorldView view, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        if (chunkMaxX < chunkMinX || chunkMaxZ < chunkMinZ) {
            throw new IllegalArgumentException("empty area " + chunkMinX + "," + chunkMinZ + ".." + chunkMaxX + "," + chunkMaxZ);
        }
        final long sections = ((long) chunkMaxX - chunkMinX + 3) * ((long) chunkMaxZ - chunkMinZ + 3) * (view.height() >> 4);
        if (sections > MAX_SECTIONS) {
            throw new IllegalArgumentException(sections + " sections with the ring around the area, more than " + MAX_SECTIONS);
        }
        return new Area(view, chunkMinX - 1, chunkMinZ - 1, chunkMaxX - chunkMinX + 3, chunkMaxZ - chunkMinZ + 3)
                .compute(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
    }

    /** Light of the 3x3 chunks around {@code (chunkX, chunkZ)}; the view must hold the 5x5 around it. */
    public static LightResult relightArea(WorldView view, int chunkX, int chunkZ) {
        return computeArea(view, chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1);
    }

    /**
     * The padded rectangle one computation works on, sections stored apart so that air costs nothing. Section
     * {@code s = (chunkX * depth + chunkZ) * sections + sectionY}, all three local; a cell is {@code s << 12 | i}
     * with {@code i = x | z << 4 | y << 8} inside its section.
     */
    private static final class Area {
        private final WorldView view;
        private final int originX, originZ, width, depth, sections;
        private final boolean[] loaded;
        private final char[][] states;
        private final int[] skyHeight;
        private byte[][] light;
        private final int[][] queues = new int[16][];
        private final int[] queueSizes = new int[16];

        Area(WorldView view, int originX, int originZ, int width, int depth) {
            this.view = view;
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.depth = depth;
            this.sections = view.height() >> 4;
            this.loaded = new boolean[width * depth];
            this.states = new char[width * depth * sections][];
            this.skyHeight = new int[width * 16 * depth * 16];
            for (int i = 0; i < queues.length; i++) queues[i] = new int[1024];
        }

        LightResult compute(int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
            read();
            final byte[][] sky = new byte[(chunkMaxX - chunkMinX + 1) * (chunkMaxZ - chunkMinZ + 1) * sections][];
            final byte[][] block = new byte[sky.length][];

            light = new byte[states.length][];
            skySources();
            propagate();
            collect(sky, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);

            light = new byte[states.length][];
            blockSources();
            propagate();
            collect(block, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);

            return new LightResult(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, Math.floorDiv(view.minY(), 16), sections, sky, block);
        }

        private void read() {
            final int minY = view.minY();
            for (int cx = 0; cx < width; cx++) {
                for (int cz = 0; cz < depth; cz++) {
                    if (!view.hasChunk(originX + cx, originZ + cz)) continue;
                    loaded[cx * depth + cz] = true;
                    final int blockX = (originX + cx) << 4, blockZ = (originZ + cz) << 4;
                    for (int sy = 0; sy < sections; sy++) {
                        char[] section = null;
                        final int blockY = minY + (sy << 4);
                        for (int i = 0; i < 4096; i++) {
                            final int stateId = view.getBlock(blockX | (i & 15), blockY + (i >> 8), blockZ | ((i >> 4) & 15)).stateId();
                            if (stateId == AIR) continue;
                            if (section == null) section = new char[4096];
                            section[i] = (char) stateId;
                        }
                        states[(cx * depth + cz) * sections + sy] = section;
                    }
                }
            }
        }

        private int state(int s, int i) {
            final char[] section = states[s];
            return section == null ? AIR : section[i];
        }

        private int level(int s, int i) {
            final byte[] section = light[s];
            return section == null ? 0 : section[i];
        }

        private void setLevel(int s, int i, int level) {
            byte[] section = light[s];
            if (section == null) light[s] = section = new byte[4096];
            section[i] = (byte) level;
        }

        private void enqueue(int level, int cell) {
            int[] queue = queues[level];
            if (queueSizes[level] == queue.length) queues[level] = queue = Arrays.copyOf(queue, queue.length * 2);
            queue[queueSizes[level]++] = cell;
        }

        private static boolean skyEdgeOccluded(int top, int bottom) {
            return top == AIR ? SKY_EDGE_UNDER_AIR[bottom] : LightBlocks.skyEdgeOccluded(block(top), block(bottom));
        }

        private static Block block(int stateId) {
            return Objects.requireNonNull(Block.fromStateId(stateId));
        }

        /** Level 15 from the top of every column down to its first sky edge, queued where it can spread. */
        private void skySources() {
            final int columns = width * 16;
            final int height = sections << 4;
            for (int cx = 0; cx < width; cx++) {
                for (int cz = 0; cz < depth; cz++) {
                    final int chunk = cx * depth + cz;
                    if (!loaded[chunk]) continue;
                    int lowest = height;
                    int highest = 0;
                    for (int column = 0; column < 256; column++) {
                        final int x = column & 15, z = column >> 4;
                        int y = height - 1;
                        int above = AIR;
                        while (y >= 0) {
                            final char[] section = states[chunk * sections + (y >> 4)];
                            if (section == null) {
                                if (skyEdgeOccluded(above, AIR)) break;
                                above = AIR;
                                y = (y & ~15) - 1;
                                continue;
                            }
                            final int stateId = section[x | z << 4 | (y & 15) << 8];
                            if (skyEdgeOccluded(above, stateId)) break;
                            above = stateId;
                            y--;
                        }
                        final int sourceFrom = y + 1;
                        skyHeight[(cz * 16 + z) * columns + cx * 16 + x] = sourceFrom;
                        lowest = Math.min(lowest, sourceFrom);
                        highest = Math.max(highest, sourceFrom);
                    }
                    for (int sy = lowest >> 4; sy < sections; sy++) {
                        final int s = chunk * sections + sy;
                        if (sy << 4 >= highest) {
                            light[s] = SKY_SOURCES;
                            continue;
                        }
                        for (int column = 0; column < 256; column++) {
                            final int from = skyHeight[(cz * 16 + (column >> 4)) * columns + cx * 16 + (column & 15)];
                            for (int y = Math.max(from, sy << 4); y < (sy + 1) << 4; y++) setLevel(s, column | (y & 15) << 8, 15);
                        }
                    }
                }
            }
            // A source spreads only into a cell that is not a source itself: the one under the column, and the
            // cells beside it that sit below the neighbouring column's sources.
            for (int cx = 0; cx < width; cx++) {
                for (int cz = 0; cz < depth; cz++) {
                    final int chunk = cx * depth + cz;
                    if (!loaded[chunk]) continue;
                    for (int column = 0; column < 256; column++) {
                        final int x = cx * 16 + (column & 15), z = cz * 16 + (column >> 4);
                        final int from = skyHeight[z * columns + x];
                        int to = from + 1;
                        if (x > 0 && loaded[((x - 1) >> 4) * depth + cz]) to = Math.max(to, skyHeight[z * columns + x - 1]);
                        if (x < columns - 1 && loaded[((x + 1) >> 4) * depth + cz]) to = Math.max(to, skyHeight[z * columns + x + 1]);
                        if (z > 0 && loaded[cx * depth + ((z - 1) >> 4)]) to = Math.max(to, skyHeight[(z - 1) * columns + x]);
                        if (z < depth * 16 - 1 && loaded[cx * depth + ((z + 1) >> 4)]) to = Math.max(to, skyHeight[(z + 1) * columns + x]);
                        for (int y = from; y < Math.min(to, height); y++) {
                            enqueue(15, (chunk * sections + (y >> 4)) << 12 | column | (y & 15) << 8);
                        }
                    }
                }
            }
        }

        private void blockSources() {
            for (int s = 0; s < states.length; s++) {
                final char[] section = states[s];
                if (section == null) continue;
                for (int i = 0; i < 4096; i++) {
                    final int emission = EMISSION[section[i]];
                    if (emission <= 0) continue;
                    setLevel(s, i, emission);
                    enqueue(emission, s << 12 | i);
                }
            }
        }

        /** Spreads the queued levels, highest first, so every cell is final when it is taken off its queue. */
        private void propagate() {
            for (int level = 15; level > 1; level--) {
                final int[] queue = queues[level];
                final int size = queueSizes[level];
                for (int k = 0; k < size; k++) {
                    final int cell = queue[k];
                    final int s = cell >>> 12, i = cell & 4095;
                    if (level(s, i) != level) continue;
                    final int from = state(s, i);
                    for (int d = 0; d < DIRECTIONS.length; d++) {
                        final int neighbour = neighbour(s, i, d);
                        if (neighbour < 0) continue;
                        final int ns = neighbour >>> 12, ni = neighbour & 4095;
                        final int current = level(ns, ni);
                        if (current >= level - 1) continue;
                        final int to = state(ns, ni);
                        final int reached = level - OPACITY[to];
                        if (reached <= current) continue;
                        if ((SHAPED[from] || SHAPED[to]) && LightBlocks.occludes(block(from), block(to), FACES[d])) continue;
                        setLevel(ns, ni, reached);
                        if (reached > 1) enqueue(reached, neighbour);
                    }
                }
                queueSizes[level] = 0;
            }
        }

        /** The cell next to {@code (s, i)} in direction {@code d}, or -1 past the area, the world or a missing chunk. */
        private int neighbour(int s, int i, int d) {
            final int cell = s << 12 | i;
            return switch (DIRECTIONS[d]) {
                case DOWN -> {
                    if ((i & 0xF00) != 0) yield cell - 256;
                    yield s % sections == 0 ? -1 : (s - 1) << 12 | i | 0xF00;
                }
                case UP -> {
                    if ((i & 0xF00) != 0xF00) yield cell + 256;
                    yield s % sections == sections - 1 ? -1 : (s + 1) << 12 | (i & 0xFF);
                }
                case NORTH -> {
                    if ((i & 0xF0) != 0) yield cell - 16;
                    final int chunk = s / sections;
                    yield chunk % depth == 0 || !loaded[chunk - 1] ? -1 : (s - sections) << 12 | i | 0xF0;
                }
                case SOUTH -> {
                    if ((i & 0xF0) != 0xF0) yield cell + 16;
                    final int chunk = s / sections;
                    yield chunk % depth == depth - 1 || !loaded[chunk + 1] ? -1 : (s + sections) << 12 | (i & ~0xF0);
                }
                case WEST -> {
                    if ((i & 0xF) != 0) yield cell - 1;
                    final int chunk = s / sections;
                    yield chunk < depth || !loaded[chunk - depth] ? -1 : (s - depth * sections) << 12 | i | 0xF;
                }
                case EAST -> {
                    if ((i & 0xF) != 0xF) yield cell + 1;
                    final int chunk = s / sections;
                    yield chunk >= (width - 1) * depth || !loaded[chunk + depth] ? -1 : (s + depth * sections) << 12 | (i & ~0xF);
                }
            };
        }

        private void collect(byte[][] out, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
            int k = 0;
            for (int chunkX = chunkMinX; chunkX <= chunkMaxX; chunkX++) {
                for (int chunkZ = chunkMinZ; chunkZ <= chunkMaxZ; chunkZ++) {
                    final int chunk = (chunkX - originX) * depth + chunkZ - originZ;
                    for (int sy = 0; sy < sections; sy++) {
                        final byte[] levels = loaded[chunk] ? light[chunk * sections + sy] : null;
                        out[k++] = levels == null ? LightCompute.EMPTY_CONTENT
                                : levels == SKY_SOURCES ? LightCompute.CONTENT_FULLY_LIT : pack(levels);
                    }
                }
            }
        }

        private static byte[] pack(byte[] levels) {
            final byte[] nibbles = new byte[2048];
            for (int i = 0; i < 4096; i += 2) nibbles[i >> 1] = (byte) (levels[i] | levels[i + 1] << 4);
            return nibbles;
        }
    }
}
