package io.github.bearisbug.synaxis.game.protocol;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement.Tile;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

/**
 * The inputs of the frozen far view vectors of {@code docs/DESIGN-M3.md} §24, which
 * {@link FarViewVectorGenerator} writes and {@link FarViewVectorTest} holds the codecs to.
 * {@code farview-vectors/README.md} describes each file.
 * <p>
 * {@code pack-small.hfp} is tile (-1, 0) at base level 2 of a map whose bounds are chunks x -2..-1,
 * z 0..1: 8 × 8 fine samples in fine block (15, 0) and 2 × 2 coarse samples in coarse block (3, 0),
 * every other sample of those two blocks an empty column of biome 0, every other block absent. The
 * fine samples cover the five kinds of column, a tie for the coarse sample and a hidden area of
 * blocks x -12..-5, z 20..27 replaced by the rule of {@code API-M3-014}. The block states are the
 * 26.2 global palette ids, {@value #BLOCK_STATES} of them.
 */
final class FarViewVectors {
    static final int STONE = 1, GRASS = 9, DIRT = 10, WATER = 86, SAND = 118, RED_CONCRETE = 15_044;
    static final int BLOCK_STATES = 32_366;
    static final List<String> BIOMES = List.of("minecraft:forest", "minecraft:plains", "minecraft:river");
    static final int FOREST = 0, PLAINS = 1, RIVER = 2;

    static final int TILE_X = -1, TILE_Z = 0, BASE_LEVEL = 2, MIN_Y = -64, HEIGHT = 256, PROTOCOL = 776;
    /** The fine block and the coarse block of the map, and where its samples start in them. */
    static final int FINE_BX = 15, FINE_BZ = 0, FINE_X0 = 56, COARSE_BX = 3, COARSE_BZ = 0, COARSE_X0 = 62;
    /** The fine samples the hidden area replaces, x and z 5..6 of the 8 × 8. */
    static final int HIDDEN_MIN = 5, HIDDEN_MAX = 6;

    static final String ONE_TILE = "announce-one-tile.bin";
    static final String FOUR_TILES = "announce-four-tiles.bin";
    static final String NINE_TILES = "announce-nine-tiles.bin";
    static final String PACK = "pack-small.hfp";
    static final String COARSE_PACK = "pack-small.coarse.hfp";
    static final String SAMPLES = "pack-small.samples.txt";

    private FarViewVectors() {
    }

    // The pack

    private static Sample ground(int state, int y, int biome) {
        return new Sample(state, 0, y, 0, 0, biome);
    }

    /** The 8 × 8 fine samples of the map before the hidden area is replaced, {@code [z][x]}. */
    static Sample[][] fineBeforeHidden() {
        final Sample[][] fine = new Sample[8][8];
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                if (x < 4 && z < 4) fine[z][x] = ground(GRASS, 128, PLAINS);
                else if (z < 4) fine[z][x] = ground(GRASS, 130, FOREST);
                else if (x < 4) fine[z][x] = ground(GRASS, 126 + (x + z) % 4, FOREST);
                else fine[z][x] = ground(GRASS, 131 + (x * 3 + z) % 5, PLAINS);
            }
        }
        // Chunk -2,0: the five kinds of column
        fine[0][0] = new Sample(0, WATER, 0, 140, 142, RIVER);
        fine[0][1] = new Sample(0, WATER, 0, 0, 10, RIVER);
        fine[0][2] = new Sample(0, 0, 0, 0, 0, RIVER);
        fine[3][3] = new Sample(SAND, WATER, 120, 121, 124, RIVER);
        // Chunk -1,0: three at the top, the coarse sample is the first by z, then x
        fine[0][6] = ground(GRASS, 150, FOREST);
        fine[0][7] = ground(STONE, 150, PLAINS);
        fine[1][5] = ground(DIRT, 150, PLAINS);
        // Chunk -1,1: a tower in the hidden area, a pond on its rim
        for (int z = HIDDEN_MIN; z <= HIDDEN_MAX; z++) {
            for (int x = HIDDEN_MIN; x <= HIDDEN_MAX; x++) fine[z][x] = ground(RED_CONCRETE, 200, FOREST);
        }
        fine[6][4] = new Sample(SAND, WATER, 125, 126, 129, RIVER);
        return fine;
    }

    /**
     * The fine samples as the pack has them: the hidden ones are stone of plains at the lowest top
     * of the samples around them, all of which are inside the bounds.
     */
    static Sample[][] fine() {
        final Sample[][] fine = fineBeforeHidden();
        int rim = Integer.MAX_VALUE;
        for (int z = HIDDEN_MIN - 1; z <= HIDDEN_MAX + 1; z++) {
            for (int x = HIDDEN_MIN - 1; x <= HIDDEN_MAX + 1; x++) {
                final boolean hidden = x >= HIDDEN_MIN && x <= HIDDEN_MAX && z >= HIDDEN_MIN && z <= HIDDEN_MAX;
                if (!hidden) rim = Math.min(rim, Math.max(0, fine[z][x].top()));
            }
        }
        for (int z = HIDDEN_MIN; z <= HIDDEN_MAX; z++) {
            for (int x = HIDDEN_MIN; x <= HIDDEN_MAX; x++) fine[z][x] = ground(STONE, rim, PLAINS);
        }
        return fine;
    }

    /**
     * The 2 × 2 coarse samples, {@code [z][x]}: each chunk's through {@link FarViewPack#coarsen} from
     * the fine samples before the hidden area, the chunk of the hidden area then replaced on the coarse
     * grid. Its rim reaches past the bounds, empty columns that count as 0, so it is stone at 0.
     */
    static Sample[][] coarse() {
        final Sample[][] fine = fineBeforeHidden();
        final Sample[][] coarse = new Sample[2][2];
        for (int cz = 0; cz < 2; cz++) {
            for (int cx = 0; cx < 2; cx++) {
                final List<Sample> chunk = new ArrayList<>(16);
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) chunk.add(fine[cz * 4 + z][cx * 4 + x]);
                }
                coarse[cz][cx] = FarViewPack.coarsen(BASE_LEVEL, chunk);
            }
        }
        coarse[1][1] = ground(STONE, 0, PLAINS);
        return coarse;
    }

    /** One 64 × 64 block holding {@code samples} from {@code (x0, 0)} on, empty columns of biome 0 elsewhere. */
    static List<Sample> block(Sample[][] samples, int x0) {
        final Sample[] block = new Sample[FarViewPack.BLOCK_SAMPLES];
        Arrays.fill(block, Sample.EMPTY);
        for (int z = 0; z < samples.length; z++) {
            for (int x = 0; x < samples[z].length; x++) block[z * FarViewPack.BLOCK_SIDE + x0 + x] = samples[z][x];
        }
        return List.of(block);
    }

    static FarViewPack.Writer packSmall() {
        return new FarViewPack.Writer(BASE_LEVEL, TILE_X, TILE_Z, MIN_Y, HEIGHT, BLOCK_STATES, PROTOCOL, BIOMES)
                .block(Layer.COARSE, COARSE_BX, COARSE_BZ, block(coarse(), COARSE_X0))
                .block(Layer.FINE, FINE_BX, FINE_BZ, block(fine(), FINE_X0));
    }

    /**
     * {@code pack-small.samples.txt}: the header, the biome table, the present blocks, and every sample
     * of the map in both layers by its sample coordinate in the tile.
     */
    static String samplesText(FarViewPack.Header header) {
        final StringBuilder text = new StringBuilder()
                .append("# pack-small.hfp: the expected content, docs/DESIGN-M3.md §24. Written by FarViewVectorGenerator.\n")
                .append("# Tile -1,0 at base level 2; the map is chunks x -2..-1, z 0..1. The hidden area is blocks\n")
                .append("# x -12..-5, z 20..27: fine samples 1021..1022, 5..6 and the coarse sample of chunk -1,1 are replaced.\n")
                .append("# <layer> <sample x in the tile> <sample z in the tile> <ground_state> <fluid_state> <ground_y>")
                .append(" <fluid_bottom> <fluid_top> <biome>\n")
                .append("# Every other sample of the two present blocks is an empty column of biome 0.\n")
                .append("header base_level=").append(header.baseLevel()).append(" coarse_level=").append(header.coarseLevel())
                .append(" tile_x=").append(header.tileX()).append(" tile_z=").append(header.tileZ())
                .append(" min_y=").append(header.minY()).append(" height=").append(header.height())
                .append(" block_states=").append(header.blockStates()).append(" protocol_version=")
                .append(header.protocolVersion()).append(" fine_offset=").append(header.fineOffset()).append('\n')
                .append("biomes ").append(String.join(" ", BIOMES)).append('\n')
                .append("block coarse ").append(COARSE_BX).append(' ').append(COARSE_BZ).append('\n')
                .append("block fine ").append(FINE_BX).append(' ').append(FINE_BZ).append('\n');
        appendSamples(text, "coarse", coarse(), COARSE_BX * 64 + COARSE_X0, COARSE_BZ * 64);
        appendSamples(text, "fine", fine(), FINE_BX * 64 + FINE_X0, FINE_BZ * 64);
        return text.toString();
    }

    private static void appendSamples(StringBuilder text, String layer, Sample[][] samples, int x0, int z0) {
        for (int z = 0; z < samples.length; z++) {
            for (int x = 0; x < samples[z].length; x++) {
                final Sample s = samples[z][x];
                text.append(layer).append(' ').append(x0 + x).append(' ').append(z0 + z).append(' ')
                        .append(s.groundState()).append(' ').append(s.fluidState()).append(' ').append(s.groundY())
                        .append(' ').append(s.fluidBottom()).append(' ').append(s.fluidTop()).append(' ')
                        .append(s.biome()).append('\n');
            }
        }
    }

    // The announcements

    static byte[] sha256(String label) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(label.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Tile tile(String root, int x, int z, int layer, long bytes) {
        final byte[] digest = sha256("tile " + x + " " + z + " layer " + layer);
        return new Tile(x, z, layer, bytes, digest, root + "/vectors/farview/" + HexFormat.of().formatHex(digest) + ".hfp");
    }

    static FarViewAnnouncement oneTile() {
        return new FarViewAnnouncement("vectors-0123456789ab-p776", 2, 4, 0, 256,
                List.of(tile("https://cdn.test/synaxis", 0, 0, FarViewAnnouncement.LAYER_FULL, 8_912_896)));
    }

    /** A region that is not aligned to the tiles: four whole tiles around the origin, base level 1. */
    static FarViewAnnouncement fourTiles() {
        final List<Tile> tiles = new ArrayList<>();
        for (int x = -1; x <= 0; x++) {
            for (int z = -1; z <= 0; z++) {
                tiles.add(tile("http://127.0.0.1:25751", x, z, FarViewAnnouncement.LAYER_FULL, 33_554_432L + x * 1000L + z));
            }
        }
        return new FarViewAnnouncement("vectors-fedcba987654-p776", 1, 4, -64, 256, tiles);
    }

    /** An aligned region with regions all round: tile 0,0 whole and the eight around it by their coarse prefix. */
    static FarViewAnnouncement nineTiles() {
        final List<Tile> tiles = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                tiles.add(x == 0 && z == 0
                        ? tile("http://host.docker.internal:25751", x, z, FarViewAnnouncement.LAYER_FULL, 2_345_678)
                        : tile("http://host.docker.internal:25751", x, z, FarViewAnnouncement.LAYER_COARSE, 98_765 + x * 10L + z));
            }
        }
        return new FarViewAnnouncement("farview-0123456789ab-p776", 2, 4, -64, 256, tiles);
    }

    static Map<String, FarViewAnnouncement> announcements() {
        final Map<String, FarViewAnnouncement> announcements = new TreeMap<>();
        announcements.put(ONE_TILE, oneTile());
        announcements.put(FOUR_TILES, fourTiles());
        announcements.put(NINE_TILES, nineTiles());
        return announcements;
    }

    static byte[] encode(FarViewAnnouncement announcement) {
        final MsgBuf buf = MsgBuf.writing();
        announcement.write(buf);
        return buf.toByteArray();
    }

    /** The head of {@link #nineTiles()} with another tile list, written field by field. */
    private static byte[] rawAnnouncement(List<int[]> tiles, int urlBytes) {
        return rawAnnouncement(tiles, urlBytes, 98_765);
    }

    /** The same with every tile of {@code bytes}. */
    private static byte[] rawAnnouncement(List<int[]> tiles, int urlBytes, long bytes) {
        final MsgBuf buf = MsgBuf.writing();
        buf.writeVarInt(FarViewAnnouncement.FORMAT_VERSION);
        buf.writeString("farview-0123456789ab-p776");
        buf.writeUnsignedByte(2);
        buf.writeUnsignedByte(4);
        buf.writeVarInt(-64);
        buf.writeVarInt(256);
        buf.writeVarInt(tiles.size());
        for (int[] tile : tiles) {
            buf.writeVarInt(tile[0]);
            buf.writeVarInt(tile[1]);
            buf.writeUnsignedByte(tile[2]);
            buf.writeVarLong(bytes);
            buf.writeBytes(sha256("tile " + tile[0] + " " + tile[1] + " layer " + tile[2]));
            final String root = "http://host.docker.internal:25751/farview/farview/";
            buf.writeString(root + "x".repeat(Math.max(0, urlBytes - root.length())));
        }
        return buf.toByteArray();
    }

    /** The edge announcements, each a decode failure. */
    static Map<String, byte[]> edgeAnnouncements() {
        final Map<String, byte[]> edges = new TreeMap<>();
        final List<int[]> many = new ArrayList<>();
        for (int x = 0; x < 65; x++) many.add(new int[]{x, 0, x == 0 ? 0 : 1});
        edges.put("announce-count-65.bin", rawAnnouncement(many, 80));
        edges.put("announce-url-too-long.bin", rawAnnouncement(List.of(new int[]{0, 0, 0}),
                FarViewAnnouncement.MAX_URL_BYTES + 1));
        edges.put("announce-duplicate-tile.bin", rawAnnouncement(List.of(new int[]{0, 0, 0}, new int[]{0, 0, 1}), 80));
        edges.put("announce-layer-2.bin", rawAnnouncement(List.of(new int[]{0, 0, 0}, new int[]{0, 1, 2}), 80));
        edges.put("announce-no-full-tile.bin", rawAnnouncement(List.of(new int[]{-1, 0, 1}, new int[]{0, 0, 1}), 80));
        edges.put("announce-object-over-40mib.bin", rawAnnouncement(List.of(new int[]{0, 0, 0}), 80, 41_943_041L));
        return edges;
    }

    // The edge packs, each pack-small with a few bytes changed

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Where the index entry of a block is. */
    private static int entry(FarViewPack.Header header, Layer layer, int bx, int bz) {
        final int biomeTable = BIOMES.stream().mapToInt(name -> 1 + name.length()).sum();
        final int start = layer == Layer.COARSE ? FarViewPack.HEADER_BYTES + biomeTable : (int) header.fineOffset();
        final int side = FarViewPack.blocksPerSide(layer == Layer.COARSE ? header.coarseLevel() : header.baseLevel());
        return start + (bz * side + bx) * FarViewPack.INDEX_ENTRY_BYTES;
    }

    /** The pack with its fine block, the last thing in the file, replaced by {@code raw} compressed as the writer does. */
    private static byte[] withFineBlock(byte[] pack, FarViewPack.Header header, FarViewPack.Block fine, byte[] raw) {
        final byte[] compressed = deflate(raw);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(pack, 0, (int) fine.offset());
        out.write(compressed, 0, compressed.length);
        final byte[] bytes = out.toByteArray();
        le(bytes).putInt(entry(header, Layer.FINE, fine.bx(), fine.bz()) + 4, compressed.length);
        return bytes;
    }

    static byte[] deflate(byte[] raw) {
        final Deflater deflater = new Deflater(FarViewPack.COMPRESSION_LEVEL);
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

    /** The fine block of pack-small inflated, as the layout lays the samples out. */
    static byte[] rawFineBlock() {
        final ByteBuffer raw = ByteBuffer.allocate(FarViewPack.BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (Sample s : block(fine(), FINE_X0)) {
            raw.putShort((short) s.groundState()).putShort((short) s.fluidState()).put((byte) s.groundY())
                    .put((byte) s.fluidBottom()).put((byte) s.fluidTop()).put((byte) s.biome());
        }
        return raw.array();
    }

    /** The edge packs, each refused by {@link FarViewPack.Reader}. */
    static Map<String, byte[]> edgePacks(byte[] pack) {
        final FarViewPack.Reader reader;
        try {
            reader = FarViewPack.Reader.read(pack);
        } catch (FarViewPack.Invalid e) {
            throw new IllegalStateException(e);
        }
        final FarViewPack.Header header = reader.header();
        final FarViewPack.Block coarse = reader.blocks(Layer.COARSE).getFirst();
        final FarViewPack.Block fine = reader.blocks(Layer.FINE).getFirst();
        final Map<String, byte[]> edges = new TreeMap<>();

        final byte[] version = pack.clone();
        le(version).putShort(4, (short) 2);
        edges.put("pack-format-version-2.hfp", version);
        final byte[] flags = pack.clone();
        le(flags).putShort(6, (short) 1);
        edges.put("pack-flags.hfp", flags);
        final byte[] baseLevel = pack.clone();
        baseLevel[8] = 3;
        edges.put("pack-base-level-3.hfp", baseLevel);
        final byte[] coarseLevel = pack.clone();
        coarseLevel[10] = 3;
        edges.put("pack-coarse-level-3.hfp", coarseLevel);
        final byte[] fineOffset = pack.clone();
        le(fineOffset).putInt(32, pack.length + 1);
        edges.put("pack-fine-offset-past-end.hfp", fineOffset);
        // The coarse block's entry points at the fine block's bytes, after fine_offset
        final byte[] coarseAfter = pack.clone();
        le(coarseAfter).putInt(entry(header, Layer.COARSE, coarse.bx(), coarse.bz()), (int) fine.offset())
                .putInt(entry(header, Layer.COARSE, coarse.bx(), coarse.bz()) + 4, (int) fine.length());
        edges.put("pack-coarse-block-past-fine-offset.hfp", coarseAfter);
        // The fine block's entry one byte longer than the file
        final byte[] pastEnd = pack.clone();
        le(pastEnd).putInt(entry(header, Layer.FINE, fine.bx(), fine.bz()) + 4, (int) fine.length() + 1);
        edges.put("pack-index-past-end.hfp", pastEnd);
        // Fine block 14,0 points at the bytes of 15,0
        final byte[] overlap = pack.clone();
        le(overlap).putInt(entry(header, Layer.FINE, fine.bx() - 1, fine.bz()), (int) fine.offset())
                .putInt(entry(header, Layer.FINE, fine.bx() - 1, fine.bz()) + 4, (int) fine.length());
        edges.put("pack-overlapping-blocks.hfp", overlap);
        // The fine block one byte short when inflated
        edges.put("pack-block-not-32768.hfp", withFineBlock(pack, header, fine,
                Arrays.copyOf(rawFineBlock(), FarViewPack.BLOCK_BYTES - 1)));
        // The pond of chunk -2,0 with its fluid one block above the ground
        final byte[] raw = rawFineBlock();
        raw[(3 * FarViewPack.BLOCK_SIDE + FINE_X0 + 3) * FarViewPack.SAMPLE_BYTES + 5]++;
        edges.put("pack-invariant.hfp", withFineBlock(pack, header, fine, raw));
        return edges;
    }

    // The JDK

    /**
     * The SHA-256 of what {@link Deflater} at level 9 makes of a fixed 64 KiB input: the written pack
     * bytes are only compared on a JDK whose zlib gives the fingerprint frozen in manifest.txt.
     */
    static String zlibFingerprint() {
        final byte[] input = new byte[64 * 1024];
        int state = 12345;
        for (int i = 0; i < input.length; i++) {
            state = state * 1103515245 + 12345;
            input[i] = (byte) ((i & 0x300) == 0 ? state >>> 24 : i >>> 5);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(deflate(input)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code protocol/src/test/resources/farview-vectors/}. */
    static Path dir() {
        final URL url = FarViewVectors.class.getResource("/farview-vectors/manifest.txt");
        if (url == null) throw new IllegalStateException("the frozen far view vectors are missing from the test resources");
        try {
            return Path.of(url.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
