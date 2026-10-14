package io.github.bearisbug.synaxis.game.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The far view height field pack of {@code docs/DESIGN-M3.md} {@code API-M3-014}: one file per
 * far view tile of 4,096 × 4,096 world blocks ({@code tile = chunk >> 8}), which the baker writes and
 * the client reads. This class is the only codec of the layout, and the coarse sample rule
 * {@link #coarsen} lives here too, so that the baker and the test fixtures take it from one place.
 * <p>
 * A pack holds two layers of samples. A fine sample covers {@code 2^b × 2^b} world blocks
 * ({@code b = base_level}, 1 or 2), a coarse sample one chunk ({@value #COARSE_LEVEL}). Each layer is cut
 * into pack blocks of 64 × 64 samples, each one zlib stream; "block" alone below means a pack block.
 * The file is the header, the biome table, the coarse block index and the coarse blocks, and from
 * {@code fine_offset} on the fine block index and the fine blocks. The first {@code fine_offset}
 * bytes, the coarse prefix, are a pack of their own with the coarse layer alone, distributed as an
 * object of their own. Every number is little endian, as in {@code .synx}.
 * <p>
 * Pure JDK like the rest of this package: {@link Deflater} and {@link Inflater} only.
 */
public final class FarViewPack {

    /** The layout this class reads and writes; a pack of any other is not read. */
    public static final int FORMAT_VERSION = 1;
    /** The first four bytes of every pack, in ASCII. */
    public static final String MAGIC = "SHFP";
    /** The fixed header in front of the biome table. */
    public static final int HEADER_BYTES = 64;
    /** A tile is this many world blocks square; {@link #blocksPerSide} counts pack blocks. */
    public static final int TILE_BLOCKS = 4096;
    /** {@code tile = chunk >> TILE_SHIFT}: a tile is 256 chunks square. */
    public static final int TILE_SHIFT = 8;
    /** The only coarse level of this layout: a coarse sample covers one chunk. */
    public static final int COARSE_LEVEL = 4;
    /** A block is this many samples square, as a DH data source is columns ({@code FullDataSourceV2.WIDTH}). */
    public static final int BLOCK_SIDE = 64;
    /** The samples of one block, {@code sz × 64 + sx}. */
    public static final int BLOCK_SAMPLES = BLOCK_SIDE * BLOCK_SIDE;
    /** One sample on disk. */
    public static final int SAMPLE_BYTES = 8;
    /** One block inflated. */
    public static final int BLOCK_BYTES = BLOCK_SAMPLES * SAMPLE_BYTES;
    /** One entry of a block index: {@code offset u32, length u32}, both 0 for an absent block. */
    public static final int INDEX_ENTRY_BYTES = 8;
    /** The most names the biome table holds. */
    public static final int MAX_BIOMES = 255;
    /** The {@link Deflater} level every block is compressed with. */
    public static final int COMPRESSION_LEVEL = 9;

    /** The largest y index plus one: a y index is one unsigned byte. */
    private static final int MAX_HEIGHT = 256;
    private static final int CHUNK_SHIFT = 4;

    private FarViewPack() {
    }

    /**
     * The tile a chunk is in.
     *
     * @param chunk a chunk coordinate, x or z
     * @return the tile coordinate on the same axis, negative for negative chunks
     */
    public static int tile(int chunk) {
        return chunk >> TILE_SHIFT;
    }

    /**
     * How many pack blocks a layer has on each side of the tile: 4 for the coarse layer, 16 for base
     * level 2 and 32 for base level 1.
     *
     * @param level the level of the layer: a base level 1 or 2, or the coarse level 4
     * @return {@code 4096 >> (level + 6)}
     * @throws IllegalArgumentException for any other level
     */
    public static int blocksPerSide(int level) {
        if (level != 1 && level != 2 && level != COARSE_LEVEL) {
            throw new IllegalArgumentException("level " + level + " is not 1, 2 or " + COARSE_LEVEL);
        }
        return (TILE_BLOCKS >> level) / BLOCK_SIDE;
    }

    /**
     * Reads {@code fine_offset} from the header of a pack, for a reader that streams the pack
     * instead of handing it whole to {@link Reader#read}. The header is not checked.
     *
     * @param header the pack from its first byte at index 0, at least 36 bytes of it; its position
     *               and byte order do not matter and are left as they are
     * @return {@code fine_offset}, the u32 at byte 32: where the fine block index starts, which is the
     * length of the coarse prefix
     */
    public static long fineOffset(ByteBuffer header) {
        return Integer.toUnsignedLong(header.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(32));
    }

    /** The two layers of a pack. */
    public enum Layer {
        /** One sample per chunk, level {@value #COARSE_LEVEL}; all of it is in the coarse prefix. */
        COARSE,
        /** One sample per {@code 2^base_level} world blocks square, from {@code fine_offset} on. */
        FINE
    }

    /**
     * The fixed header of a pack, as read; {@code magic}, {@code format_version} and {@code flags}
     * are not in it, a pack is only read when they are this layout's.
     *
     * @param baseLevel       b, 1 or 2
     * @param coarseLevel     c, {@value #COARSE_LEVEL}
     * @param tileX           the tile, as in the file name
     * @param tileZ           the tile, as in the file name
     * @param minY            {@code world.min_y} of the manifest
     * @param height          {@code world.height} of the manifest
     * @param blockStates     {@code Block.statesCount()} when it was baked; a state id is a global palette id below it
     * @param protocolVersion the protocol of the release, 776
     * @param fineOffset      where the fine block index starts, which is the length of the coarse prefix
     */
    public record Header(int baseLevel, int coarseLevel, int tileX, int tileZ, int minY, int height, long blockStates,
                         int protocolVersion, long fineOffset) {
    }

    /**
     * One sample, 8 bytes on disk in this order. The constructor holds the invariants of the layout
     * that need no context; the biome table, the block state count and the world height are checked
     * by the writer and the reader.
     *
     * @param groundState the block state of the top of the ground, 0 for a column without ground
     * @param fluidState  the block state of the top of the fluid, 0 for a column without fluid
     * @param groundY     the y index ({@code y - min_y}) of the top of the ground, 0 without ground
     * @param fluidBottom the y index of the lowest fluid block, 0 without fluid
     * @param fluidTop    the y index of the highest fluid block, 0 without fluid
     * @param biome       the index into the biome table
     */
    public record Sample(int groundState, int fluidState, int groundY, int fluidBottom, int fluidTop, int biome) {
        /** An empty column of biome 0, which every sample outside the bounds is. */
        public static final Sample EMPTY = new Sample(0, 0, 0, 0, 0, 0);

        /**
         * Checks the field widths and the invariants: with ground and fluid the fluid starts right
         * above the ground and does not end below it; fluid alone does not end below its bottom and
         * has no ground height; ground alone has no fluid heights; an empty column has no heights.
         *
         * @throws IllegalArgumentException if a field is out of its width or an invariant is broken
         */
        public Sample {
            if ((groundState & ~0xFFFF) != 0 || (fluidState & ~0xFFFF) != 0) {
                throw new IllegalArgumentException("a block state is outside [0, 65535]: " + groundState + ", " + fluidState);
            }
            if (((groundY | fluidBottom | fluidTop | biome) & ~0xFF) != 0) {
                throw new IllegalArgumentException("a y index or the biome is outside [0, 255]: " + groundY + ", "
                        + fluidBottom + ", " + fluidTop + ", " + biome);
            }
            final String broken = broken(groundState, fluidState, groundY, fluidBottom, fluidTop);
            if (!broken.isEmpty()) throw new IllegalArgumentException(broken);
        }

        /**
         * Whether the column has ground.
         *
         * @return {@code ground_state != 0}
         */
        public boolean hasGround() {
            return groundState != 0;
        }

        /**
         * Whether the column has fluid.
         *
         * @return {@code fluid_state != 0}
         */
        public boolean hasFluid() {
            return fluidState != 0;
        }

        /**
         * The top of the column, by which both downsampling steps pick their sample.
         *
         * @return the larger of {@code ground_y} and {@code fluid_top} of the parts the column has,
         * -1 for an empty column
         */
        public int top() {
            // With ground under it the fluid starts at ground_y + 1, so fluid_top is the larger
            if (hasFluid()) return fluidTop;
            return hasGround() ? groundY : -1;
        }

        /**
         * The sample as the pack stores it.
         *
         * @return its 8 bytes read as one little endian long
         */
        public long packed() {
            return groundState | (long) fluidState << 16 | (long) groundY << 32 | (long) fluidBottom << 40
                    | (long) fluidTop << 48 | (long) biome << 56;
        }

        /** What is wrong with these fields, empty when nothing is. */
        static String broken(int groundState, int fluidState, int groundY, int fluidBottom, int fluidTop) {
            final boolean ground = groundState != 0, fluid = fluidState != 0;
            if (ground && fluid) {
                if (fluidBottom != groundY + 1 || fluidBottom > fluidTop) {
                    return "ground and fluid: fluid_bottom %d is not ground_y %d + 1, or is above fluid_top %d"
                            .formatted(fluidBottom, groundY, fluidTop);
                }
            } else if (fluid) {
                if (groundY != 0 || fluidBottom > fluidTop) {
                    return "fluid alone: ground_y %d is not 0, or fluid_bottom %d is above fluid_top %d"
                            .formatted(groundY, fluidBottom, fluidTop);
                }
            } else if (ground) {
                if (fluidBottom != 0 || fluidTop != 0) {
                    return "ground alone: fluid_bottom %d and fluid_top %d are not 0".formatted(fluidBottom, fluidTop);
                }
            } else if (groundY != 0 || fluidBottom != 0 || fluidTop != 0) {
                return "an empty column has the heights %d, %d, %d".formatted(groundY, fluidBottom, fluidTop);
            }
            return "";
        }
    }

    /**
     * One present block of a layer: an index entry that is not {@code 0, 0}.
     *
     * @param layer  the layer
     * @param bx     the block in the tile, from the west
     * @param bz     the block in the tile, from the north
     * @param offset where its zlib stream starts, from the start of the file
     * @param length the length of its zlib stream
     */
    public record Block(Layer layer, int bx, int bz, long offset, long length) {
    }

    /**
     * The coarse sample of one chunk ({@code API-M3-014} rule 7): of its fine samples before any
     * hidden area is replaced, the one with the highest {@link Sample#top()}, ties going to the first
     * by z and then x, taken as it is.
     *
     * @param baseLevel b, 1 or 2
     * @param fine      the {@code (16 >> b)²} fine samples of the chunk, by z and then x
     * @return one of {@code fine}
     * @throws IllegalArgumentException for another base level or another number of samples
     */
    public static Sample coarsen(int baseLevel, List<Sample> fine) {
        if (baseLevel != 1 && baseLevel != 2) throw new IllegalArgumentException("base_level " + baseLevel + " is not 1 or 2");
        final int side = (1 << CHUNK_SHIFT) >> baseLevel;
        if (fine.size() != side * side) {
            throw new IllegalArgumentException(fine.size() + " fine samples, a chunk has " + side * side + " at base level "
                    + baseLevel);
        }
        Sample highest = fine.getFirst();
        for (Sample sample : fine) {
            if (sample.top() > highest.top()) highest = sample;
        }
        return highest;
    }

    /**
     * Writes one pack. Blocks are added in any order and compressed as they come; absent blocks
     * stay absent. The bytes do not depend on the order of the calls.
     */
    public static final class Writer {
        private final int baseLevel;
        private final int tileX;
        private final int tileZ;
        private final int minY;
        private final int height;
        private final int blockStates;
        private final int protocolVersion;
        private final List<String> biomes;
        private final byte[][] coarse;
        private final byte[][] fine;

        /**
         * Starts a pack with every block absent.
         *
         * @param baseLevel       b, 1 or 2
         * @param tileX           the tile's x, {@code chunk_x >> 8} ({@link #tile}), as in the header and
         *                        the file name
         * @param tileZ           the tile's z, the same way
         * @param minY            {@code world.min_y}, a 16 bit signed number
         * @param height          {@code world.height}, 1 to 256: a y index is one byte
         * @param blockStates     the block state count of the registry, at least 1
         * @param protocolVersion the protocol of the release
         * @param biomes          the biome table: 1 to 255 names of 1 to 255 bytes in UTF-8, strictly
         *                        ascending by {@link String#compareTo}
         * @throws IllegalArgumentException if a value does not fit the layout
         */
        public Writer(int baseLevel, int tileX, int tileZ, int minY, int height, int blockStates, int protocolVersion,
                      List<String> biomes) {
            if (baseLevel != 1 && baseLevel != 2) throw new IllegalArgumentException("base_level " + baseLevel + " is not 1 or 2");
            if (minY != (short) minY) throw new IllegalArgumentException("min_y " + minY + " is not a 16 bit number");
            if (height < 1 || height > MAX_HEIGHT) throw new IllegalArgumentException("height " + height + " is not 1 to 256");
            if (blockStates < 1) throw new IllegalArgumentException("block_states " + blockStates + " is below 1");
            this.biomes = List.copyOf(biomes);
            if (this.biomes.isEmpty() || this.biomes.size() > MAX_BIOMES) {
                throw new IllegalArgumentException(this.biomes.size() + " biomes, the table holds 1 to " + MAX_BIOMES);
            }
            for (int i = 0; i < this.biomes.size(); i++) {
                final int length = this.biomes.get(i).getBytes(StandardCharsets.UTF_8).length;
                if (length < 1 || length > 255) throw new IllegalArgumentException("biome " + i + " is " + length + " bytes");
                if (i > 0 && this.biomes.get(i - 1).compareTo(this.biomes.get(i)) >= 0) {
                    throw new IllegalArgumentException("biome " + this.biomes.get(i) + " is not after " + this.biomes.get(i - 1));
                }
            }
            this.baseLevel = baseLevel;
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.minY = minY;
            this.height = height;
            this.blockStates = blockStates;
            this.protocolVersion = protocolVersion;
            final int coarseSide = blocksPerSide(COARSE_LEVEL), fineSide = blocksPerSide(baseLevel);
            this.coarse = new byte[coarseSide * coarseSide][];
            this.fine = new byte[fineSide * fineSide][];
        }

        /**
         * Adds a block.
         *
         * @param layer   the layer
         * @param bx      the block in the tile, from 0 to {@link #blocksPerSide} of the layer's level
         * @param bz      the block in the tile
         * @param samples the 4,096 samples, {@code sz × 64 + sx}
         * @return this writer
         * @throws IllegalArgumentException if the block is outside the tile, or a sample names a biome
         *                                  past the table, a state past the block state count, or a y
         *                                  index past the height
         * @throws IllegalStateException    if the block was added before
         */
        public Writer block(Layer layer, int bx, int bz, List<Sample> samples) {
            final int side = blocksPerSide(layer == Layer.COARSE ? COARSE_LEVEL : baseLevel);
            if (bx < 0 || bx >= side || bz < 0 || bz >= side) {
                throw new IllegalArgumentException(name(layer) + " block " + bx + "," + bz + " is outside the " + side + " × "
                        + side + " of the tile");
            }
            if (samples.size() != BLOCK_SAMPLES) {
                throw new IllegalArgumentException(samples.size() + " samples, a block has " + BLOCK_SAMPLES);
            }
            final byte[][] blocks = layer == Layer.COARSE ? coarse : fine;
            if (blocks[bz * side + bx] != null) {
                throw new IllegalStateException(name(layer) + " block " + bx + "," + bz + " was added before");
            }
            final ByteBuffer raw = ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < BLOCK_SAMPLES; i++) {
                final Sample sample = samples.get(i);
                final String problem = problem(sample.groundState(), sample.fluidState(), sample.groundY(),
                        sample.fluidBottom(), sample.fluidTop(), sample.biome(), biomes.size(), blockStates, height);
                if (!problem.isEmpty()) {
                    throw new IllegalArgumentException(name(layer) + " block " + bx + "," + bz + " sample " + i % BLOCK_SIDE
                            + "," + i / BLOCK_SIDE + ": " + problem);
                }
                raw.putLong(sample.packed());
            }
            blocks[bz * side + bx] = deflate(raw.array());
            return this;
        }

        /**
         * The coarse prefix: the header, the biome table, the coarse index and the coarse blocks, a
         * pack with the coarse layer alone.
         *
         * @return the first {@code fine_offset} bytes of {@link #write()}
         */
        public byte[] coarsePrefix() {
            final ByteArrayOutputStream table = new ByteArrayOutputStream();
            for (String biome : biomes) {
                final byte[] name = biome.getBytes(StandardCharsets.UTF_8);
                table.write(name.length);
                table.writeBytes(name);
            }
            final int indexStart = HEADER_BYTES + table.size();
            final byte[] index = index(coarse, indexStart + coarse.length * INDEX_ENTRY_BYTES);
            final long fineOffset = (long) indexStart + index.length + total(coarse);
            final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.put(MAGIC.getBytes(StandardCharsets.US_ASCII))
                    .putShort((short) FORMAT_VERSION)
                    .putShort((short) 0)
                    .put((byte) baseLevel)
                    .put((byte) biomes.size())
                    .put((byte) COARSE_LEVEL)
                    .put((byte) 0)
                    .putInt(tileX)
                    .putInt(tileZ)
                    .putShort((short) minY)
                    .putShort((short) height)
                    .putInt(blockStates)
                    .putInt(protocolVersion)
                    .putInt((int) fineOffset);
            // Bytes 36 to 63 are reserved: allocate leaves them 0
            final ByteArrayOutputStream out = new ByteArrayOutputStream((int) fineOffset);
            out.writeBytes(header.array());
            out.writeBytes(table.toByteArray());
            out.writeBytes(index);
            for (byte[] block : coarse) {
                if (block != null) out.writeBytes(block);
            }
            return out.toByteArray();
        }

        /**
         * The whole pack: the coarse prefix, then the fine index and the fine blocks.
         *
         * @return the bytes of the file
         */
        public byte[] write() {
            final byte[] prefix = coarsePrefix();
            final byte[] index = index(fine, (long) prefix.length + (long) fine.length * INDEX_ENTRY_BYTES);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(prefix);
            out.writeBytes(index);
            for (byte[] block : fine) {
                if (block != null) out.writeBytes(block);
            }
            return out.toByteArray();
        }

        /** The index of {@code blocks}, laid out from {@code first} on in index order. */
        private static byte[] index(byte[][] blocks, long first) {
            final ByteBuffer index = ByteBuffer.allocate(blocks.length * INDEX_ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            long offset = first;
            for (byte[] block : blocks) {
                if (block == null) {
                    index.putLong(0);
                    continue;
                }
                index.putInt((int) offset).putInt(block.length);
                offset += block.length;
            }
            return index.array();
        }

        private static long total(byte[][] blocks) {
            long total = 0;
            for (byte[] block : blocks) {
                if (block != null) total += block.length;
            }
            return total;
        }

        private static byte[] deflate(byte[] raw) {
            final Deflater deflater = new Deflater(COMPRESSION_LEVEL);
            try {
                deflater.setInput(raw);
                deflater.finish();
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] chunk = new byte[8192];
                while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
                return out.toByteArray();
            } finally {
                deflater.end();
            }
        }
    }

    /**
     * Reads one pack, whole or a coarse prefix. {@link #read} checks the header, the biome table and
     * both block indexes; a block is inflated and its samples checked when it is read, and
     * {@link #verify()} reads every one.
     */
    public static final class Reader {
        private final byte[] pack;
        private final Header header;
        private final List<String> biomes;
        private final List<Block> coarse;
        private final List<Block> fine;

        private Reader(byte[] pack, Header header, List<String> biomes, List<Block> coarse, List<Block> fine) {
            this.pack = pack;
            this.header = header;
            this.biomes = biomes;
            this.coarse = coarse;
            this.fine = fine;
        }

        /**
         * Reads the header, the biome table and the coarse block index, and the fine block index
         * unless the pack ends at {@code fine_offset}. The bytes are not copied.
         *
         * @param pack a pack or a coarse prefix
         * @return the reader
         * @throws Invalid if {@code magic}, {@code format_version}, {@code flags}, {@code base_level},
         *                 {@code coarse_level} or the biome table are not this layout's, if
         *                 {@code fine_offset} is past the end, or if an index entry points outside its
         *                 layer's part of the file or into another block
         */
        public static Reader read(byte[] pack) throws Invalid {
            if (pack.length < HEADER_BYTES) {
                throw new Invalid("the pack is " + pack.length + " bytes, shorter than its " + HEADER_BYTES + " byte header");
            }
            final ByteBuffer buffer = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN);
            if (!new String(pack, 0, 4, StandardCharsets.ISO_8859_1).equals(MAGIC)) throw new Invalid("magic is not " + MAGIC);
            final int formatVersion = Short.toUnsignedInt(buffer.getShort(4));
            if (formatVersion != FORMAT_VERSION) {
                throw new Invalid("format_version " + formatVersion + " is not " + FORMAT_VERSION);
            }
            final int flags = Short.toUnsignedInt(buffer.getShort(6));
            if (flags != 0) throw new Invalid("flags 0x%04x are not 0".formatted(flags));
            final int baseLevel = Byte.toUnsignedInt(buffer.get(8));
            if (baseLevel != 1 && baseLevel != 2) throw new Invalid("base_level " + baseLevel + " is not 1 or 2");
            final int biomeCount = Byte.toUnsignedInt(buffer.get(9));
            if (biomeCount == 0) throw new Invalid("biome_count is 0");
            final int coarseLevel = Byte.toUnsignedInt(buffer.get(10));
            if (coarseLevel != COARSE_LEVEL) throw new Invalid("coarse_level " + coarseLevel + " is not " + COARSE_LEVEL);
            final long fineOffset = fineOffset(buffer);
            if (fineOffset > pack.length) {
                throw new Invalid("fine_offset " + fineOffset + " is past the end of the " + pack.length + " byte pack");
            }
            final int tileX = buffer.getInt(12), tileZ = buffer.getInt(16);
            final int minY = buffer.getShort(20), height = Short.toUnsignedInt(buffer.getShort(22));
            final long blockStates = Integer.toUnsignedLong(buffer.getInt(24));
            final int protocolVersion = buffer.getInt(28);
            final Header header = new Header(baseLevel, coarseLevel, tileX, tileZ, minY, height, blockStates, protocolVersion,
                    fineOffset);

            final List<String> biomes = new ArrayList<>(biomeCount);
            int at = HEADER_BYTES;
            for (int i = 0; i < biomeCount; i++) {
                final int length = at < fineOffset ? Byte.toUnsignedInt(pack[at]) : -1;
                if (length < 1 || at + 1 + length > fineOffset) {
                    throw new Invalid("biome " + i + " of the table is empty or runs past fine_offset " + fineOffset);
                }
                final String name;
                try {
                    name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(pack, at + 1, length)).toString();
                } catch (CharacterCodingException _) {
                    throw new Invalid("biome " + i + " of the table is not UTF-8");
                }
                if (!biomes.isEmpty() && biomes.getLast().compareTo(name) >= 0) {
                    throw new Invalid("biome " + name + " of the table is not after " + biomes.getLast());
                }
                biomes.add(name);
                at += 1 + length;
            }

            final int coarseSide = blocksPerSide(COARSE_LEVEL);
            final long coarseIndexEnd = at + (long) coarseSide * coarseSide * INDEX_ENTRY_BYTES;
            if (coarseIndexEnd > fineOffset) {
                throw new Invalid("fine_offset " + fineOffset + " is inside the coarse block index, which ends at " + coarseIndexEnd);
            }
            final List<Block> coarse = blocks(buffer, Layer.COARSE, at, coarseSide, coarseIndexEnd, fineOffset);
            List<Block> fine = List.of();
            if (fineOffset < pack.length) {
                final int fineSide = blocksPerSide(baseLevel);
                final long fineIndexEnd = fineOffset + (long) fineSide * fineSide * INDEX_ENTRY_BYTES;
                if (fineIndexEnd > pack.length) {
                    throw new Invalid("the fine block index ends at " + fineIndexEnd + ", past the end of the " + pack.length
                            + " byte pack");
                }
                fine = blocks(buffer, Layer.FINE, (int) fineOffset, fineSide, fineIndexEnd, pack.length);
            }
            return new Reader(pack, header, List.copyOf(biomes), coarse, fine);
        }

        /**
         * The present blocks of one index, each between {@code first} and {@code end} and none
         * overlapping another, in index order.
         */
        private static List<Block> blocks(ByteBuffer buffer, Layer layer, int indexStart, int side, long first, long end)
                throws Invalid {
            final List<Block> blocks = new ArrayList<>();
            for (int bz = 0; bz < side; bz++) {
                for (int bx = 0; bx < side; bx++) {
                    final int entry = indexStart + (bz * side + bx) * INDEX_ENTRY_BYTES;
                    final long offset = Integer.toUnsignedLong(buffer.getInt(entry));
                    final long length = Integer.toUnsignedLong(buffer.getInt(entry + 4));
                    if (offset == 0 && length == 0) continue;
                    if (length == 0 || offset < first || offset + length > end) {
                        final String where = name(layer) + " block " + bx + "," + bz + " at " + offset + "+" + length;
                        if (layer == Layer.COARSE) {
                            throw new Invalid(where + " is not between the end of its index " + first + " and fine_offset " + end);
                        }
                        if (offset + length > end) throw new Invalid(where + " is past the end of the " + end + " byte pack");
                        throw new Invalid(where + " is not after the end of its index " + first);
                    }
                    blocks.add(new Block(layer, bx, bz, offset, length));
                }
            }
            final List<Block> byOffset = new ArrayList<>(blocks);
            byOffset.sort(Comparator.comparingLong(Block::offset));
            for (int i = 1; i < byOffset.size(); i++) {
                final Block before = byOffset.get(i - 1), after = byOffset.get(i);
                if (before.offset() + before.length() > after.offset()) {
                    throw new Invalid(name(layer) + " block " + after.bx() + "," + after.bz() + " overlaps " + name(layer)
                            + " block " + before.bx() + "," + before.bz());
                }
            }
            return List.copyOf(blocks);
        }

        /**
         * The header.
         *
         * @return the header as read
         */
        public Header header() {
            return header;
        }

        /**
         * The biome table, the names a sample's {@code biome} indexes.
         *
         * @return the names in table order
         */
        public List<String> biomes() {
            return biomes;
        }

        /**
         * Whether this is a coarse prefix: the pack ends at {@code fine_offset}. A tile announced whole
         * whose pack reads as one is not taken ({@code API-M3-014} 读取).
         *
         * @return true without a fine layer
         */
        public boolean coarseOnly() {
            return header.fineOffset() == pack.length;
        }

        /**
         * The present blocks of a layer: no fine block in a coarse prefix.
         *
         * @param layer the layer
         * @return the blocks by {@code bz} and then {@code bx}
         */
        public List<Block> blocks(Layer layer) {
            return layer == Layer.COARSE ? coarse : fine;
        }

        /**
         * Inflates a block and checks every sample: the invariants of {@link Sample}, a biome inside
         * the table, both states below {@code block_states} and every y index below {@code height}.
         *
         * @param block one of {@link #blocks}
         * @return the 4,096 samples, {@code sz × 64 + sx}
         * @throws Invalid                  if the block is not one zlib stream of exactly
         *                                  {@value #BLOCK_BYTES} bytes, or a sample breaks an invariant
         * @throws IllegalArgumentException if the block is not one of this pack's
         */
        public List<Sample> samples(Block block) throws Invalid {
            if (!blocks(block.layer()).contains(block)) throw new IllegalArgumentException(block + " is not a block of this pack");
            final ByteBuffer raw = ByteBuffer.wrap(inflate(block)).order(ByteOrder.LITTLE_ENDIAN);
            final Sample[] samples = new Sample[BLOCK_SAMPLES];
            for (int i = 0; i < BLOCK_SAMPLES; i++) {
                final int groundState = Short.toUnsignedInt(raw.getShort()), fluidState = Short.toUnsignedInt(raw.getShort());
                final int groundY = Byte.toUnsignedInt(raw.get()), fluidBottom = Byte.toUnsignedInt(raw.get());
                final int fluidTop = Byte.toUnsignedInt(raw.get()), biome = Byte.toUnsignedInt(raw.get());
                final String problem = problem(groundState, fluidState, groundY, fluidBottom, fluidTop, biome, biomes.size(),
                        header.blockStates(), header.height());
                if (!problem.isEmpty()) {
                    throw new Invalid(name(block.layer()) + " block " + block.bx() + "," + block.bz() + " sample "
                            + i % BLOCK_SIDE + "," + i / BLOCK_SIDE + " breaks an invariant: " + problem);
                }
                samples[i] = new Sample(groundState, fluidState, groundY, fluidBottom, fluidTop, biome);
            }
            return List.of(samples);
        }

        /**
         * Reads every block of both layers.
         *
         * @throws Invalid if any is not one
         */
        public void verify() throws Invalid {
            for (Block block : coarse) samples(block);
            for (Block block : fine) samples(block);
        }

        private byte[] inflate(Block block) throws Invalid {
            final String what = name(block.layer()) + " block " + block.bx() + "," + block.bz();
            final Inflater inflater = new Inflater();
            try {
                inflater.setInput(pack, (int) block.offset(), (int) block.length());
                // One byte over a block: a stream that inflates to more fills it and fails the check below
                final byte[] out = new byte[BLOCK_BYTES + 1];
                int inflated = 0;
                while (inflated < out.length && !inflater.finished()) {
                    final int read = inflater.inflate(out, inflated, out.length - inflated);
                    if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                    inflated += read;
                }
                if (!inflater.finished() || inflated != BLOCK_BYTES || inflater.getRemaining() != 0) {
                    throw new Invalid(what + " does not inflate to exactly " + BLOCK_BYTES + " bytes in one zlib stream of "
                            + block.length() + " bytes");
                }
                return Arrays.copyOf(out, BLOCK_BYTES);
            } catch (DataFormatException e) {
                throw new Invalid(what + " is not a zlib stream: " + e.getMessage());
            } finally {
                inflater.end();
            }
        }
    }

    /** What is wrong with a sample in its pack, empty when nothing is. */
    private static String problem(int groundState, int fluidState, int groundY, int fluidBottom, int fluidTop, int biome,
                                  int biomeCount, long blockStates, int height) {
        final String broken = Sample.broken(groundState, fluidState, groundY, fluidBottom, fluidTop);
        if (!broken.isEmpty()) return broken;
        if (biome >= biomeCount) return "biome " + biome + " is not below biome_count " + biomeCount;
        if (groundState >= blockStates || fluidState >= blockStates) {
            return "block state " + Math.max(groundState, fluidState) + " is not below block_states " + blockStates;
        }
        if (Math.max(groundY, fluidTop) >= height) {
            return "y index " + Math.max(groundY, fluidTop) + " is not below height " + height;
        }
        return "";
    }

    private static String name(Layer layer) {
        return layer == Layer.COARSE ? "coarse" : "fine";
    }

    /** A pack that is not one of this layout; the client takes it as no pack ({@code API-M3-015} 下载). */
    @SuppressWarnings("serial") // never serialized
    public static final class Invalid extends Exception {
        /**
         * Creates the exception.
         *
         * @param message what about the pack is not this layout
         */
        public Invalid(String message) {
            super(message);
        }
    }
}
