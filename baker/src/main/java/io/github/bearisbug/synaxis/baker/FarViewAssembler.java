package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.baker.anvil.HiddenArea;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.DynamicRegistry;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.world.biome.Biome;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The far view packs of a bake ({@code docs/DESIGN-M3.md} {@code API-M3-014}). Each region task writes the fine
 * samples of its chunks into a part file under {@code farview-parts/}; once every region is baked this class
 * takes the coarse samples from them ({@link FarViewPack#coarsen}), replaces the hidden areas on both layers,
 * and writes one pack for every tile meeting the bounds, each with its own biome table.
 * <p>
 * Until a tile's table exists a sample's {@code biome} is the biome's registry id. In the part files and the
 * grids of a tile a sample is one long, {@link #packed}; a slot left 0 is an empty column of biome 0, which is
 * what every sample outside the bounds is.
 */
final class FarViewAssembler {
    /** {@code base_level} of every pack the baker writes (§28.1). */
    static final int BASE_LEVEL = 2;
    /** Fine samples along one side of a chunk. */
    static final int CHUNK_SAMPLES = 16 >> BASE_LEVEL;
    /** {@code chunk = fine sample >> CHUNK_SHIFT}. */
    static final int CHUNK_SHIFT = 4 - BASE_LEVEL;
    /** The directory of the part files in the work directory. */
    static final String PARTS = "farview-parts";

    /** Fine samples along one side of a tile. */
    private static final int TILE_FINE = FarViewPack.TILE_BLOCKS >> BASE_LEVEL;
    /** Chunks, which are coarse samples, along one side of a tile. */
    private static final int TILE_CHUNKS = 1 << FarViewPack.TILE_SHIFT;
    private static final int PER_CHUNK = CHUNK_SAMPLES * CHUNK_SAMPLES;
    private static final int PART_HEADER = 16;

    /**
     * A test's change to the samples of a tile just before they are written; a bake passes {@link #NONE}.
     */
    @FunctionalInterface
    interface Tamper {
        Tamper NONE = (_, _, _, _) -> {
        };

        /**
         * Changes the samples of one tile.
         *
         * @param coarse the coarse samples of the tile, {@code cz × 256 + cx}
         * @param fine   the fine samples of the tile, {@code sz × 1024 + sx}
         */
        void apply(int tileX, int tileZ, long[] coarse, long[] fine);
    }

    /** The top of a sample of one layer by its grid coordinates, an empty column counting as 0. */
    @FunctionalInterface
    interface CellTop {
        int top(int x, int z) throws IOException;
    }

    /**
     * One hidden area on one layer's grid, inclusive, and the height its samples are replaced with.
     */
    record Replacement(int minX, int minZ, int maxX, int maxZ, int height) {
        boolean covers(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private final SynxManifest.Bounds bounds;
    private final int minY;
    private final int height;
    private final int protocolVersion;
    private final List<HiddenArea> hidden;
    /** Biome names by registry id. */
    private final String[] biomeNames;
    private final int plains;

    /**
     * @param hidden the hidden areas of the source, corners as min and max
     */
    FarViewAssembler(SynxManifest.Bounds bounds, int minY, int height, int protocolVersion,
                     List<HiddenArea> hidden, DynamicRegistry<Biome> biomes) {
        this.bounds = bounds;
        this.minY = minY;
        this.height = height;
        this.protocolVersion = protocolVersion;
        this.hidden = List.copyOf(hidden);
        this.biomeNames = new String[biomes.size()];
        for (int id = 0; id < biomeNames.length; id++) {
            biomeNames[id] = Objects.requireNonNull(biomes.getKey(id), () -> "biome id without a key").key().asString();
        }
        this.plains = biomes.getId(Biome.PLAINS);
    }

    /** The tiles meeting the bounds, by tile x and then tile z. */
    static List<int[]> tiles(SynxManifest.Bounds bounds) {
        final List<int[]> tiles = new ArrayList<>();
        for (int tileX = FarViewPack.tile(bounds.minX()); tileX <= FarViewPack.tile(bounds.maxX()); tileX++) {
            for (int tileZ = FarViewPack.tile(bounds.minZ()); tileZ <= FarViewPack.tile(bounds.maxZ()); tileZ++) {
                tiles.add(new int[]{tileX, tileZ});
            }
        }
        return tiles;
    }

    /** {@code farview/t.<tile_x>.<tile_z>.hfp}, relative to the build directory. */
    static String fileName(int tileX, int tileZ) {
        return "t." + tileX + "." + tileZ + ".hfp";
    }

    // Samples as longs

    /** The 8 bytes of a sample read as one little endian long, as the pack stores it. */
    static long packed(Sample sample) {
        return sample.groundState() | (long) sample.fluidState() << 16 | (long) sample.groundY() << 32
                | (long) sample.fluidBottom() << 40 | (long) sample.fluidTop() << 48 | (long) sample.biome() << 56;
    }

    static Sample sample(long packed) {
        return new Sample((int) (packed & 0xFFFF), (int) (packed >>> 16 & 0xFFFF), (int) (packed >>> 32 & 0xFF),
                (int) (packed >>> 40 & 0xFF), (int) (packed >>> 48 & 0xFF), (int) (packed >>> 56));
    }

    /** {@link Sample#top()}, an empty column counting as 0, which is what the hidden rule takes it for. */
    static int hiddenTop(long packed) {
        return Math.max(0, sample(packed).top());
    }

    // Part files

    /**
     * Writes the part of one region: the chunk rectangle, then for each of its chunks, x before z, the fine
     * samples by z and then x.
     *
     * @param chunks the fine samples of every chunk of the rectangle, in that order
     */
    static void writePart(Path work, int regionX, int regionZ, int minX, int minZ, int maxX, int maxZ,
                          List<List<Sample>> chunks) throws IOException {
        final ByteBuffer part = ByteBuffer.allocate(PART_HEADER + chunks.size() * PER_CHUNK * 8).order(ByteOrder.LITTLE_ENDIAN);
        part.putInt(minX).putInt(minZ).putInt(maxX).putInt(maxZ);
        for (List<Sample> chunk : chunks) {
            for (Sample sample : chunk) part.putLong(packed(sample));
        }
        Files.write(partFile(work.resolve(PARTS), regionX, regionZ), part.array());
    }

    private static Path partFile(Path parts, int regionX, int regionZ) {
        return parts.resolve("r." + regionX + "." + regionZ + ".part");
    }

    /** The samples of one region part, {@link #sample(int, int, int)}. */
    private record Part(int minX, int minZ, int maxX, int maxZ, long[] samples) {
        long sample(int chunkX, int chunkZ, int index) {
            return samples[((chunkX - minX) * (maxZ - minZ + 1) + chunkZ - minZ) * PER_CHUNK + index];
        }
    }

    private static Part part(Path parts, int regionX, int regionZ) throws IOException {
        final ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(partFile(parts, regionX, regionZ)))
                .order(ByteOrder.LITTLE_ENDIAN);
        final int minX = bytes.getInt(), minZ = bytes.getInt(), maxX = bytes.getInt(), maxZ = bytes.getInt();
        final long[] samples = new long[bytes.remaining() / 8];
        bytes.asLongBuffer().get(samples);
        return new Part(minX, minZ, maxX, maxZ, samples);
    }

    private boolean inBounds(int chunkX, int chunkZ) {
        return chunkX >= bounds.minX() && chunkX <= bounds.maxX() && chunkZ >= bounds.minZ() && chunkZ <= bounds.maxZ();
    }

    // The hidden rule

    /**
     * {@code API-M3-014} 隐藏区域 on one layer: the samples of the layer's grid meeting a hidden area are replaced;
     * the replacement height of an area is the lowest top among the samples of its ring (the eight neighbours of
     * its samples) that no area meets, 0 without any.
     *
     * @param areas block coordinates, corners as min and max
     * @param level the layer's level, a sample is {@code 2^level} blocks square
     * @param top   the top of a sample of the layer before any replacement
     * @return one replacement per area, in grid coordinates
     */
    static List<Replacement> hide(List<HiddenArea> areas, int level, CellTop top) throws IOException {
        final List<Replacement> cells = new ArrayList<>(areas.size());
        for (HiddenArea area : areas) {
            cells.add(new Replacement(area.x1() >> level, area.z1() >> level, area.x2() >> level, area.z2() >> level, 0));
        }
        final List<Replacement> replacements = new ArrayList<>(cells.size());
        for (Replacement cell : cells) {
            int lowest = Integer.MAX_VALUE;
            for (int x = cell.minX() - 1; x <= cell.maxX() + 1; x++) {
                lowest = Math.min(lowest, ringTop(cells, top, x, cell.minZ() - 1));
                lowest = Math.min(lowest, ringTop(cells, top, x, cell.maxZ() + 1));
            }
            for (int z = cell.minZ(); z <= cell.maxZ(); z++) {
                lowest = Math.min(lowest, ringTop(cells, top, cell.minX() - 1, z));
                lowest = Math.min(lowest, ringTop(cells, top, cell.maxX() + 1, z));
            }
            replacements.add(new Replacement(cell.minX(), cell.minZ(), cell.maxX(), cell.maxZ(),
                    lowest == Integer.MAX_VALUE ? 0 : lowest));
        }
        return List.copyOf(replacements);
    }

    /** The top of a ring sample, or {@link Integer#MAX_VALUE} for a sample an area meets, which is no ring sample. */
    private static int ringTop(List<Replacement> cells, CellTop top, int x, int z) throws IOException {
        for (Replacement cell : cells) {
            if (cell.covers(x, z)) return Integer.MAX_VALUE;
        }
        return top.top(x, z);
    }

    /**
     * The height a sample is replaced with: the lowest of the replacements covering it.
     *
     * @return -1 for a sample no replacement covers
     */
    static int replacedHeight(List<Replacement> replacements, int x, int z) {
        int lowest = -1;
        for (Replacement replacement : replacements) {
            if (replacement.covers(x, z) && (lowest < 0 || replacement.height() < lowest)) lowest = replacement.height();
        }
        return lowest;
    }

    /** What a hidden sample becomes: the default stone at the replacement height, no fluid, plains. */
    long hiddenSample(int height) {
        return packed(new Sample(Block.STONE.stateId(), 0, height, 0, 0, plains));
    }

    /** Biome names by registry id. */
    String biomeName(int registryId) {
        return biomeNames[registryId];
    }

    // Assembly

    /**
     * Writes the pack of every tile meeting the bounds into {@code farview}, each through a staging file in the
     * parts directory of the work directory that is forced to disk and hard linked into place, then deletes the
     * parts directory.
     *
     * @return the {@code farview[]} entries, by tile x and then tile z
     */
    List<SynxManifest.FarView> assemble(Path work, Path farview, Tamper tamper) throws IOException {
        final Path parts = work.resolve(PARTS);
        final long[] coarse = coarseGrid(parts);
        final int width = bounds.maxX() - bounds.minX() + 1;
        final Map<Long, Part> ring = new HashMap<>();
        final List<Replacement> fineHidden = hide(hidden, BASE_LEVEL, (x, z) -> {
            final int chunkX = x >> CHUNK_SHIFT, chunkZ = z >> CHUNK_SHIFT;
            if (!inBounds(chunkX, chunkZ)) return 0;
            final int regionX = SynxFormat.region(chunkX), regionZ = SynxFormat.region(chunkZ);
            Part part = ring.get(key(regionX, regionZ));
            if (part == null) {
                part = part(parts, regionX, regionZ);
                ring.put(key(regionX, regionZ), part);
            }
            return hiddenTop(part.sample(chunkX, chunkZ, (z & (CHUNK_SAMPLES - 1)) * CHUNK_SAMPLES + (x & (CHUNK_SAMPLES - 1))));
        });
        ring.clear();
        final List<Replacement> coarseHidden = hide(hidden, FarViewPack.COARSE_LEVEL, (x, z) -> inBounds(x, z)
                ? hiddenTop(coarse[(z - bounds.minZ()) * width + x - bounds.minX()]) : 0);

        Files.createDirectories(farview);
        final List<SynxManifest.FarView> entries = new ArrayList<>();
        for (int[] tile : tiles(bounds)) {
            entries.add(tile(parts, tile[0], tile[1], coarse, fineHidden, coarseHidden, tamper, farview));
        }
        Baker.deleteTree(parts);
        return List.copyOf(entries);
    }

    private static long key(int x, int z) {
        return (long) x << 32 | (z & 0xFFFF_FFFFL);
    }

    /** The coarse sample of every chunk of the bounds, {@code (z - minZ) × width + x - minX}. */
    private long[] coarseGrid(Path parts) throws IOException {
        final int width = bounds.maxX() - bounds.minX() + 1, depth = bounds.maxZ() - bounds.minZ() + 1;
        final long[] coarse = new long[width * depth];
        final Sample[] fine = new Sample[PER_CHUNK];
        for (int regionX = SynxFormat.region(bounds.minX()); regionX <= SynxFormat.region(bounds.maxX()); regionX++) {
            for (int regionZ = SynxFormat.region(bounds.minZ()); regionZ <= SynxFormat.region(bounds.maxZ()); regionZ++) {
                final Part part = part(parts, regionX, regionZ);
                for (int chunkX = part.minX(); chunkX <= part.maxX(); chunkX++) {
                    for (int chunkZ = part.minZ(); chunkZ <= part.maxZ(); chunkZ++) {
                        for (int i = 0; i < PER_CHUNK; i++) fine[i] = sample(part.sample(chunkX, chunkZ, i));
                        coarse[(chunkZ - bounds.minZ()) * width + chunkX - bounds.minX()] =
                                packed(FarViewPack.coarsen(BASE_LEVEL, Arrays.asList(fine)));
                    }
                }
            }
        }
        return coarse;
    }

    private SynxManifest.FarView tile(Path parts, int tileX, int tileZ, long[] coarseGrid, List<Replacement> fineHidden,
                                      List<Replacement> coarseHidden, Tamper tamper, Path farview) throws IOException {
        final int originX = tileX << FarViewPack.TILE_SHIFT, originZ = tileZ << FarViewPack.TILE_SHIFT;
        final int minX = Math.max(originX, bounds.minX()), maxX = Math.min(originX + TILE_CHUNKS - 1, bounds.maxX());
        final int minZ = Math.max(originZ, bounds.minZ()), maxZ = Math.min(originZ + TILE_CHUNKS - 1, bounds.maxZ());
        final int width = bounds.maxX() - bounds.minX() + 1;

        final long[] coarse = new long[TILE_CHUNKS * TILE_CHUNKS];
        final long[] fine = new long[TILE_FINE * TILE_FINE];
        for (int regionX = SynxFormat.region(minX); regionX <= SynxFormat.region(maxX); regionX++) {
            for (int regionZ = SynxFormat.region(minZ); regionZ <= SynxFormat.region(maxZ); regionZ++) {
                final Part part = part(parts, regionX, regionZ);
                for (int chunkX = part.minX(); chunkX <= part.maxX(); chunkX++) {
                    for (int chunkZ = part.minZ(); chunkZ <= part.maxZ(); chunkZ++) {
                        final int localX = chunkX - originX, localZ = chunkZ - originZ;
                        coarse[localZ * TILE_CHUNKS + localX] =
                                coarseGrid[(chunkZ - bounds.minZ()) * width + chunkX - bounds.minX()];
                        for (int i = 0; i < PER_CHUNK; i++) {
                            final int sx = localX * CHUNK_SAMPLES + i % CHUNK_SAMPLES;
                            final int sz = localZ * CHUNK_SAMPLES + i / CHUNK_SAMPLES;
                            fine[sz * TILE_FINE + sx] = part.sample(chunkX, chunkZ, i);
                        }
                    }
                }
            }
        }
        // Only samples inside the bounds are replaced: the others are empty columns whatever an area covers
        replace(fine, fineHidden, originX * CHUNK_SAMPLES, originZ * CHUNK_SAMPLES, TILE_FINE,
                minX * CHUNK_SAMPLES, minZ * CHUNK_SAMPLES, (maxX + 1) * CHUNK_SAMPLES - 1, (maxZ + 1) * CHUNK_SAMPLES - 1);
        replace(coarse, coarseHidden, originX, originZ, TILE_CHUNKS, minX, minZ, maxX, maxZ);
        tamper.apply(tileX, tileZ, coarse, fine);

        // The biome table: every biome a sample inside the bounds names, on either layer
        final boolean[] used = new boolean[biomeNames.length];
        for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
            for (int chunkX = minX; chunkX <= maxX; chunkX++) {
                used[(int) (coarse[(chunkZ - originZ) * TILE_CHUNKS + chunkX - originX] >>> 56)] = true;
                for (int i = 0; i < PER_CHUNK; i++) {
                    final int sx = (chunkX - originX) * CHUNK_SAMPLES + i % CHUNK_SAMPLES;
                    final int sz = (chunkZ - originZ) * CHUNK_SAMPLES + i / CHUNK_SAMPLES;
                    used[(int) (fine[sz * TILE_FINE + sx] >>> 56)] = true;
                }
            }
        }
        final List<String> table = new ArrayList<>();
        for (int id = 0; id < used.length; id++) {
            if (used[id]) table.add(biomeNames[id]);
        }
        table.sort(null);
        final int[] index = new int[biomeNames.length];
        for (int id = 0; id < used.length; id++) {
            if (used[id]) index[id] = table.indexOf(biomeNames[id]);
        }

        final FarViewPack.Writer writer = new FarViewPack.Writer(BASE_LEVEL, tileX, tileZ, minY, height, Block.statesCount(),
                protocolVersion, table);
        final int coarseSide = FarViewPack.blocksPerSide(FarViewPack.COARSE_LEVEL);
        for (int bz = 0; bz < coarseSide; bz++) {
            for (int bx = 0; bx < coarseSide; bx++) {
                final int blockX = originX + bx * FarViewPack.BLOCK_SIDE, blockZ = originZ + bz * FarViewPack.BLOCK_SIDE;
                if (!meets(blockX, blockZ, FarViewPack.BLOCK_SIDE)) continue;
                writer.block(Layer.COARSE, bx, bz, block(coarse, TILE_CHUNKS, bx, bz, index, originX, originZ, 0));
            }
        }
        final int fineSide = FarViewPack.blocksPerSide(BASE_LEVEL), chunksPerBlock = FarViewPack.BLOCK_SIDE / CHUNK_SAMPLES;
        for (int bz = 0; bz < fineSide; bz++) {
            for (int bx = 0; bx < fineSide; bx++) {
                if (!meets(originX + bx * chunksPerBlock, originZ + bz * chunksPerBlock, chunksPerBlock)) continue;
                writer.block(Layer.FINE, bx, bz, block(fine, TILE_FINE, bx, bz, index, originX, originZ, CHUNK_SHIFT));
            }
        }

        final byte[] prefix = writer.coarsePrefix();
        final byte[] whole = writer.write();
        final Path staging = parts.resolve(fileName(tileX, tileZ) + ".staging");
        try {
            Files.write(staging, whole);
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // A hard link fails on an existing name, where a rename would replace it
            Files.createLink(farview.resolve(fileName(tileX, tileZ)), staging);
        } finally {
            Files.deleteIfExists(staging);
        }
        return new SynxManifest.FarView(tileX, tileZ, BASE_LEVEL, FarViewPack.COARSE_LEVEL, whole.length,
                SynxDigests.sha256(whole), prefix.length, SynxDigests.sha256(prefix));
    }

    /** Whether the square of {@code side} chunks from the given one meets the bounds. */
    private boolean meets(int chunkX, int chunkZ, int side) {
        return chunkX <= bounds.maxX() && chunkX + side - 1 >= bounds.minX()
                && chunkZ <= bounds.maxZ() && chunkZ + side - 1 >= bounds.minZ();
    }

    /**
     * Replaces the hidden samples of a tile's grid inside the given rectangle of grid coordinates.
     *
     * @param originX the grid coordinate of the tile's first sample
     */
    private void replace(long[] grid, List<Replacement> replacements, int originX, int originZ, int side,
                         int minX, int minZ, int maxX, int maxZ) {
        for (Replacement replacement : replacements) {
            for (int z = Math.max(minZ, replacement.minZ()); z <= Math.min(maxZ, replacement.maxZ()); z++) {
                for (int x = Math.max(minX, replacement.minX()); x <= Math.min(maxX, replacement.maxX()); x++) {
                    grid[(z - originZ) * side + x - originX] = hiddenSample(replacedHeight(replacements, x, z));
                }
            }
        }
    }

    /**
     * One block of a tile's grid with each sample's biome turned into its index in the table; samples outside
     * the bounds are empty columns of biome 0.
     *
     * @param originX the tile's first chunk
     * @param shift   {@code chunk = grid coordinate >> shift} on this layer
     */
    private List<Sample> block(long[] grid, int side, int bx, int bz, int[] index, int originX, int originZ, int shift) {
        final Sample[] samples = new Sample[FarViewPack.BLOCK_SAMPLES];
        for (int sz = 0; sz < FarViewPack.BLOCK_SIDE; sz++) {
            for (int sx = 0; sx < FarViewPack.BLOCK_SIDE; sx++) {
                final int x = bx * FarViewPack.BLOCK_SIDE + sx, z = bz * FarViewPack.BLOCK_SIDE + sz;
                Sample sample = Sample.EMPTY;
                if (inBounds(originX + (x >> shift), originZ + (z >> shift))) {
                    final Sample named = sample(grid[z * side + x]);
                    sample = new Sample(named.groundState(), named.fluidState(), named.groundY(), named.fluidBottom(),
                            named.fluidTop(), index[named.biome()]);
                }
                samples[sz * FarViewPack.BLOCK_SIDE + sx] = sample;
            }
        }
        return Arrays.asList(samples);
    }
}
