package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTimePacket;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link FarViewShardFixture} serves, without a game port: the synthetic packs and their
 * announcement, the points and camera spots of {@code /expect.json} in {@code synthetic} and
 * {@code baked}, and the HTTP side ({@code Range}, the shared rate, {@code half} and {@code break-sha}).
 * The container cases of track C stand on these.
 */
@Timeout(120)
public class FarViewShardFixtureTest {

    private static FarViewShardFixture.Content content;

    @BeforeAll
    static void synthesize() {
        content = FarViewShardFixture.Content.synthetic("farview-0123456789ab-p776", "http://127.0.0.1:1", false);
    }

    private static FarViewPack.Reader reader(byte[] pack) {
        try {
            return FarViewPack.Reader.read(pack);
        } catch (FarViewPack.Invalid e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void synthesizedAreOneWholeTileAndTheEightAroundByTheirCoarsePrefix() {
        final FarViewAnnouncement announcement = FarViewAnnouncement.read(MsgBuf.reading(content.announcement()));
        assertEquals(-64, announcement.minY());
        assertEquals(256, announcement.height());
        assertEquals(9, announcement.tiles().size());
        for (FarViewAnnouncement.Tile tile : announcement.tiles()) {
            final String hex = SynxDigests.hex(tile.sha256());
            final byte[] object = content.served().get(hex);
            assertNotNull(object, tile.toString());
            assertArrayEquals(tile.sha256(), SynxDigests.sha256(object));
            assertEquals(tile.bytes(), object.length);
            assertEquals("http://127.0.0.1:1/farview/farview/" + hex + ".hfp", tile.url());
            final boolean whole = tile.tileX() == 0 && tile.tileZ() == 0;
            assertEquals(whole ? FarViewAnnouncement.LAYER_FULL : FarViewAnnouncement.LAYER_COARSE, tile.layer());
            final FarViewPack.Reader reader = reader(object);
            assertEquals(!whole, reader.coarseOnly(), tile.toString());
            assertEquals(Block.statesCount(), reader.header().blockStates());
            assertEquals(16, reader.blocks(Layer.COARSE).size());
            assertEquals(whole ? 256 : 0, reader.blocks(Layer.FINE).size());
        }
    }

    /** The coarse layers come out of {@link FarViewPack#coarsen} on the same function's fine samples. */
    @Test
    public void theCoarseSamplesAreTheHighestFineSampleOfTheirChunk() {
        final byte[] west = content.packs().get("-1 0");
        for (int[] chunk : new int[][]{{-38, 18}, {-1, 0}, {-256, 255}, {-100, 7}}) {
            final List<Sample> fine = new ArrayList<>();
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    fine.add(FarViewShardFixture.Synthetic.column(chunk[0] * 16 + x * 4, chunk[1] * 16 + z * 4));
                }
            }
            assertEquals(FarViewPack.coarsen(2, fine), FarViewShardFixture.sampleAt(west, Layer.COARSE, chunk[0] * 16, chunk[1] * 16),
                    chunk[0] + "," + chunk[1]);
        }
    }

    @Test
    public void theHiddenAreaShowsInNeitherLayer() {
        final byte[] whole = content.packs().get("0 0");
        final FarViewPack.Reader reader = reader(whole);
        final Sample tower = FarViewShardFixture.Synthetic.column(1232, 1232);
        assertEquals(Block.RED_CONCRETE.stateId(), tower.groundState(), "the hidden area hides a tower");
        for (Layer layer : Layer.values()) {
            for (FarViewPack.Block block : reader.blocks(layer)) {
                try {
                    assertFalse(reader.samples(block).contains(tower), layer + " " + block);
                } catch (FarViewPack.Invalid e) {
                    throw new AssertionError(e);
                }
            }
        }
        final Sample fine = FarViewShardFixture.sampleAt(whole, Layer.FINE, 1200, 1263);
        assertEquals(Block.STONE.stateId(), fine.groundState());
        assertEquals(FarViewShardFixture.Synthetic.PLAINS, fine.biome());
        assertEquals(fine, FarViewShardFixture.sampleAt(whole, Layer.FINE, 1263, 1200), "one height for the whole area");
        int rim = Integer.MAX_VALUE;
        for (int sz = 299; sz <= 316; sz++) {
            for (int sx = 299; sx <= 316; sx++) {
                if (sx > 299 && sx < 316 && sz > 299 && sz < 316) continue;
                rim = Math.min(rim, Math.max(0, FarViewShardFixture.Synthetic.column(sx * 4, sz * 4).top()));
            }
        }
        assertEquals(rim, fine.groundY(), "the lowest top of its rim");
        final Sample coarse = FarViewShardFixture.sampleAt(whole, Layer.COARSE, 1232, 1232);
        assertEquals(Block.STONE.stateId(), coarse.groundState());
    }

    @Test
    public void theExpectedPointsAreWhatThePacksHold() {
        final JsonObject expect = JsonParser.parseString(FarViewShardFixture.expectJson("synthetic", content,
                new Pos(264.5, 64, 264.5), -1)).getAsJsonObject();
        final Map<String, JsonObject> points = new TreeMap<>();
        for (JsonElement point : expect.getAsJsonArray("points")) points.put(point.getAsJsonObject().get("name").getAsString(),
                point.getAsJsonObject());
        assertEquals(List.of("hidden", "mountain", "peak", "water"), List.copyOf(points.keySet()));
        assertEquals("minecraft:stone", points.get("peak").get("block").getAsString());
        assertEquals(150, points.get("peak").get("y").getAsInt());
        assertEquals("minecraft:water", points.get("water").get("block").getAsString());
        assertEquals(60, points.get("water").get("y").getAsInt());
        assertEquals("minecraft:stone", points.get("hidden").get("block").getAsString());
        assertEquals("coarse", points.get("mountain").get("layer").getAsString());
        assertEquals(-1, points.get("mountain").get("tile_x").getAsInt());
        assertEquals(185, points.get("mountain").get("y").getAsInt(), "the summit is in the chunk of the point");
        final JsonObject edge = expect.getAsJsonObject("views").getAsJsonObject("EDGE_VIEW");
        assertEquals(164, edge.get("y").getAsDouble());
        assertEquals(90, edge.get("yaw").getAsDouble());
        assertEquals(15, edge.get("pitch").getAsDouble());
        assertEquals(5, expect.getAsJsonObject("views").size());
    }

    /**
     * A stand-in for the release {@code baked} mounts, which only the baker of track S writes: tile (0, 0)
     * whole and tile (-1, 0) by its coarse prefix (both whole on disk, as the baker writes every pack), {@code min_y} 0 and 256 high as the baker's synthetic map.
     * Terraces stepping 1 block every 16 across, a flat plain at y 80 over 2048..2559 on both axes, a lake
     * of water up to y 62 over x 1000..1199, z 600..799 (deepest over 1080..1119 × 680..719), a hill up to
     * y 200 at (3000, 1500), and in tile (-1, 0) a mountain up to y 190 at (-300, 2040).
     */
    static final class BakedLike {
        static final int[] PLAIN = {2048, 2559}, LAKE = {1000, 1199, 600, 799}, DEEP = {1080, 1119, 680, 719};
        static final int[] PEAK = {3000, 1500}, MOUNTAIN = {-300, 2040};
        static final Pos SPAWN = new Pos(2040.5, 78, 2040.5);

        private BakedLike() {
        }

        static Sample column(int x, int z) {
            if (x >= LAKE[0] && x <= LAKE[1] && z >= LAKE[2] && z <= LAKE[3]) {
                final int ground = x >= DEEP[0] && x <= DEEP[1] && z >= DEEP[2] && z <= DEEP[3] ? 50 : 55;
                return new Sample(Block.SAND.stateId(), Block.WATER.stateId(), ground, ground + 1, 62, 0);
            }
            int ground = x >= PLAIN[0] && x <= PLAIN[1] && z >= PLAIN[0] && z <= PLAIN[1] ? 80 : 70 + ((x >> 4) & 7);
            ground = Math.max(ground, 200 - Math.max(Math.abs(x - PEAK[0]), Math.abs(z - PEAK[1])) / 2);
            ground = Math.max(ground, 190 - (int) (Math.hypot(x - MOUNTAIN[0], z - MOUNTAIN[1]) / 4));
            return new Sample(ground > 120 ? Block.STONE.stateId() : Block.GRASS_BLOCK.stateId(), 0, ground, 0, 0, 0);
        }

        /** The packs written as {@code Baker} will, read back through {@link FarViewShardFixture.Content#baked}. */
        static FarViewShardFixture.Content content(Path build) throws IOException {
            final List<FarViewAnnouncement.Tile> tiles = new ArrayList<>();
            Files.createDirectories(build.resolve("farview"));
            for (int tileX = -1; tileX <= 0; tileX++) {
                final boolean whole = tileX == 0;
                final FarViewPack.Writer writer = new FarViewPack.Writer(2, tileX, 0, 0, 256, Block.statesCount(), 776,
                        List.of("minecraft:plains"));
                final int x0 = tileX * FarViewPack.TILE_BLOCKS;
                for (int bz = 0; bz < 4; bz++) {
                    for (int bx = 0; bx < 4; bx++) {
                        final List<Sample> coarse = new ArrayList<>(FarViewPack.BLOCK_SAMPLES);
                        for (int cz = bz * 64; cz < bz * 64 + 64; cz++) {
                            for (int cx = bx * 64; cx < bx * 64 + 64; cx++) {
                                final List<Sample> fine = new ArrayList<>(16);
                                for (int z = 0; z < 16; z += 4) {
                                    for (int x = 0; x < 16; x += 4) fine.add(column(x0 + cx * 16 + x, cz * 16 + z));
                                }
                                coarse.add(FarViewPack.coarsen(2, fine));
                            }
                        }
                        writer.block(Layer.COARSE, bx, bz, coarse);
                    }
                }
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        final List<Sample> fine = new ArrayList<>(FarViewPack.BLOCK_SAMPLES);
                        for (int sz = bz * 64; sz < bz * 64 + 64; sz++) {
                            for (int sx = bx * 64; sx < bx * 64 + 64; sx++) fine.add(column(x0 + sx * 4, sz * 4));
                        }
                        writer.block(Layer.FINE, bx, bz, fine);
                    }
                }
                final byte[] pack = writer.write();
                Files.write(build.resolve("farview").resolve("t." + tileX + ".0.hfp"), pack);
                final byte[] object = whole ? pack : writer.coarsePrefix();
                tiles.add(new FarViewAnnouncement.Tile(tileX, 0, whole ? FarViewAnnouncement.LAYER_FULL
                        : FarViewAnnouncement.LAYER_COARSE, object.length, SynxDigests.sha256(object), "http://127.0.0.1:1/x"));
            }
            final MsgBuf buf = MsgBuf.writing();
            new FarViewAnnouncement("farview3x3-0123456789ab-p776", 2, 4, 0, 256, tiles).write(buf);
            return FarViewShardFixture.Content.baked(build, FarViewShardFixture.BAKED_MAP_ID, buf.toByteArray());
        }
    }

    /**
     * Every sample of the packs as the client draws them, the fine layer of a whole pack and the coarse
     * layer of the others: one grid by {@code "<tile_x> <tile_z>"}, {@code [sz * side + sx]}.
     */
    private record Drawn(Map<String, Sample[]> grids, Map<String, Integer> levels, int minY) {
        static Drawn of(Map<String, byte[]> packs) throws FarViewPack.Invalid {
            final Map<String, Sample[]> grids = new HashMap<>();
            final Map<String, Integer> levels = new HashMap<>();
            int minY = 0;
            for (Map.Entry<String, byte[]> pack : packs.entrySet()) {
                final FarViewPack.Reader reader = reader(pack.getValue());
                minY = reader.header().minY();
                final Layer layer = reader.coarseOnly() ? Layer.COARSE : Layer.FINE;
                final int level = layer == Layer.COARSE ? FarViewPack.COARSE_LEVEL : reader.header().baseLevel();
                final int side = FarViewPack.TILE_BLOCKS >> level;
                final Sample[] grid = new Sample[side * side];
                Arrays.fill(grid, Sample.EMPTY);
                for (FarViewPack.Block block : reader.blocks(layer)) {
                    final List<Sample> content = reader.samples(block);
                    for (int i = 0; i < content.size(); i++) {
                        grid[(block.bz() * 64 + i / 64) * side + block.bx() * 64 + i % 64] = content.get(i);
                    }
                }
                grids.put(pack.getKey(), grid);
                levels.put(pack.getKey(), level);
            }
            return new Drawn(grids, levels, minY);
        }

        /** The sample under column x, z, null where no pack is. */
        @Nullable Sample at(int x, int z) {
            final int tileX = Math.floorDiv(x, FarViewPack.TILE_BLOCKS), tileZ = Math.floorDiv(z, FarViewPack.TILE_BLOCKS);
            final Sample[] grid = grids.get(tileX + " " + tileZ);
            if (grid == null) return null;
            final int level = levels.get(tileX + " " + tileZ), side = FarViewPack.TILE_BLOCKS >> level;
            return grid[((z - tileZ * FarViewPack.TILE_BLOCKS) >> level) * side + ((x - tileX * FarViewPack.TILE_BLOCKS) >> level)];
        }

        /** The absolute y of the top face of column x, z: one above its top block. */
        int surface(int x, int z) {
            final Sample sample = at(x, z);
            return sample == null ? Integer.MIN_VALUE : minY + sample.top() + 1;
        }

        /** Where the line of sight of a camera spot first meets the drawn ground, {@code x, z}, null when it does not. */
        int @Nullable [] landing(JsonObject view) {
            final double yaw = Math.toRadians(view.get("yaw").getAsDouble()), pitch = Math.toRadians(view.get("pitch").getAsDouble());
            final double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
            for (double t = 0; t < 2 * FarViewPack.TILE_BLOCKS; t += 0.25) {
                final double y = view.get("y").getAsDouble() + dy * t;
                if (y < minY) return null;
                final int x = (int) Math.floor(view.get("x").getAsDouble() + dx * t);
                final int z = (int) Math.floor(view.get("z").getAsDouble() + dz * t);
                if (y <= surface(x, z)) return new int[]{x, z};
            }
            return null;
        }
    }

    private static JsonObject expectBaked(FarViewShardFixture.Content baked) {
        return JsonParser.parseString(FarViewShardFixture.expectJson("baked", baked, BakedLike.SPAWN, -1)).getAsJsonObject();
    }

    /** {@code baked}: the points come out of the packs with the absolute y of their {@code min_y}, not of synthetic's -64. */
    @Test
    public void theBakedPointsHaveTheAbsoluteYOfTheirPack(@TempDir Path dir) throws IOException {
        final JsonObject expect = expectBaked(BakedLike.content(dir));
        final Map<String, JsonObject> points = new TreeMap<>();
        for (JsonElement point : expect.getAsJsonArray("points")) points.put(point.getAsJsonObject().get("name").getAsString(),
                point.getAsJsonObject());
        assertEquals(List.of("mountain", "peak", "water"), List.copyOf(points.keySet()));
        assertEquals(BakedLike.PEAK[0], points.get("peak").get("x").getAsInt());
        assertEquals(BakedLike.PEAK[1], points.get("peak").get("z").getAsInt());
        assertEquals(200, points.get("peak").get("y").getAsInt());
        assertEquals("minecraft:water", points.get("water").get("block").getAsString());
        assertEquals(62, points.get("water").get("y").getAsInt());
        assertEquals(-1, points.get("mountain").get("tile_x").getAsInt());
        assertEquals("coarse", points.get("mountain").get("layer").getAsString());
        assertEquals(190, points.get("mountain").get("y").getAsInt());
    }

    /**
     * {@code baked}: the four spots of V3b are found in the packs, not taken from synthetic's function, and
     * EDGE_VIEW looks into tile (-1, 0) from the shard's own tile even though the spawn is 2,040 blocks
     * from its west edge ({@code docs/DESIGN-M3.md} 容器验收 V1 ⑥).
     */
    @Test
    public void theBakedCameraSpotsLookAtWhatTheirNamesSay(@TempDir Path dir) throws IOException, FarViewPack.Invalid {
        final FarViewShardFixture.Content baked = BakedLike.content(dir);
        final Drawn drawn = Drawn.of(baked.packs());
        final JsonObject views = expectBaked(baked).getAsJsonObject("views");
        assertEquals(List.of("EDGE_VIEW", "edge-outward", "lake", "plain", "ridge"), List.copyOf(new TreeSet<>(views.keySet())));
        for (String name : views.keySet()) {
            final JsonObject view = views.getAsJsonObject(name);
            final int x = (int) Math.floor(view.get("x").getAsDouble()), z = (int) Math.floor(view.get("z").getAsDouble());
            assertTrue(view.get("y").getAsDouble() > drawn.surface(x, z), name + " is above the ground: " + view);
        }

        final JsonObject plain = views.getAsJsonObject("plain");
        for (String axis : new String[]{"x", "z"}) {
            final double at = plain.get(axis).getAsDouble();
            assertTrue(at >= BakedLike.PLAIN[0] + 32 && at <= BakedLike.PLAIN[1] - 32, "plain is over the plain: " + plain);
        }

        final int[] lake = drawn.landing(views.getAsJsonObject("lake"));
        assertNotNull(lake, "lake looks at the ground");
        assertTrue(Objects.requireNonNull(drawn.at(lake[0], lake[1])).hasFluid(), "lake looks at water: " + Arrays.toString(lake));

        final JsonObject ridge = views.getAsJsonObject("ridge");
        assertEquals(BakedLike.PEAK[1], Math.floor(ridge.get("z").getAsDouble()), "ridge faces the hill: " + ridge);
        assertTrue(ridge.get("x").getAsDouble() < BakedLike.PEAK[0] && ridge.get("yaw").getAsDouble() == -90,
                "ridge looks east at the hill: " + ridge);

        final JsonObject outward = views.getAsJsonObject("edge-outward");
        assertEquals(100.5, outward.get("x").getAsDouble(), "100 blocks inside the west edge of tile (0, 0)");
        assertEquals(90, outward.get("yaw").getAsDouble());

        final JsonObject edge = views.getAsJsonObject("EDGE_VIEW");
        assertEquals(90, edge.get("yaw").getAsDouble());
        assertEquals(15, edge.get("pitch").getAsDouble());
        final int[] beyond = drawn.landing(edge);
        assertNotNull(beyond, "EDGE_VIEW looks at the ground");
        assertEquals(-1, Math.floorDiv(beyond[0], FarViewPack.TILE_BLOCKS), "EDGE_VIEW looks into tile (-1, 0): "
                + Arrays.toString(beyond));
    }

    @Test
    public void breakShaServesTheWholePackWithOneByteChanged() {
        final FarViewShardFixture.Content broken = FarViewShardFixture.Content.synthetic("farview-0123456789ab-p776",
                "http://127.0.0.1:1", true);
        final FarViewAnnouncement announcement = FarViewAnnouncement.read(MsgBuf.reading(broken.announcement()));
        int differing = 0;
        for (FarViewAnnouncement.Tile tile : announcement.tiles()) {
            final byte[] served = broken.served().get(SynxDigests.hex(tile.sha256()));
            if (!Arrays.equals(SynxDigests.sha256(served), tile.sha256())) {
                differing++;
                assertEquals(FarViewAnnouncement.LAYER_FULL, tile.layer());
                assertEquals(tile.bytes(), served.length);
            }
        }
        assertEquals(1, differing);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private static HttpResponse<byte[]> get(int port, String path, @Nullable String range) throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (range != null) request.header("Range", range);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    public void thePackServerAnswersRangesAtFiftyMegabitsAndLogsEachPackRequest() throws Exception {
        final byte[] object = new byte[1_250_000];
        new Random(7).nextBytes(object);
        final String hex = SynxDigests.hex(SynxDigests.sha256(object));
        final FarViewShardFixture.PackServer server = new FarViewShardFixture.PackServer("farview", Map.of(hex, object),
                false, "{}\n");
        server.start(0);
        try {
            final String path = "/farview/farview/" + hex + ".hfp";
            final long started = System.nanoTime();
            final HttpResponse<byte[]> whole = get(server.port(), path, null);
            final double seconds = (System.nanoTime() - started) / 1e9;
            assertEquals(200, whole.statusCode());
            assertArrayEquals(object, whole.body());
            assertTrue(seconds >= object.length * 8 / 50_000_000.0, "the rate holds: " + seconds + " s");
            assertEquals("public, max-age=31536000, immutable", whole.headers().firstValue("Cache-Control").orElseThrow());

            final HttpResponse<byte[]> rest = get(server.port(), path, "bytes=1000000-");
            assertEquals(206, rest.statusCode());
            assertEquals("bytes 1000000-1249999/1250000", rest.headers().firstValue("Content-Range").orElseThrow());
            assertArrayEquals(Arrays.copyOfRange(object, 1_000_000, object.length), rest.body());
            assertEquals(416, get(server.port(), path, "bytes=1250000-").statusCode());
            assertEquals(404, get(server.port(), "/farview/farview/" + "0".repeat(64) + ".hfp", null).statusCode());
            assertEquals("{}\n", new String(get(server.port(), "/expect.json", null).body(), StandardCharsets.UTF_8));

            assertEquals(List.of("GET " + path + " - 200", "GET " + path + " bytes=1000000- 206",
                    "GET " + path + " bytes=1250000- 416", "GET /farview/farview/" + "0".repeat(64) + ".hfp - 404"),
                    server.log(), "expect.json and access.log are not pack requests");
            assertEquals(String.join("\n", server.log()) + "\n", new String(get(server.port(), "/access.log", null).body(), StandardCharsets.UTF_8));
        } finally {
            server.stop();
        }
    }

    @Test
    public void halfCutsTheFirstPackResponseAndARangeFinishesIt() throws Exception {
        final byte[] object = new byte[200_000];
        new Random(11).nextBytes(object);
        final String hex = SynxDigests.hex(SynxDigests.sha256(object));
        final FarViewShardFixture.PackServer server = new FarViewShardFixture.PackServer("farview", Map.of(hex, object),
                true, "{}\n");
        server.start(0);
        try {
            final String path = "/farview/farview/" + hex + ".hfp";
            assertThrows(IOException.class, () -> get(server.port(), path, null), "the connection closes half way");
            final HttpResponse<byte[]> rest = get(server.port(), path, "bytes=100000-");
            assertEquals(206, rest.statusCode());
            assertArrayEquals(Arrays.copyOfRange(object, 100_000, object.length), rest.body());
            assertEquals(List.of("GET " + path + " - 200 cut", "GET " + path + " bytes=100000- 206"), server.log());
        } finally {
            server.stop();
        }
    }

    // ---- M3-S track C: the hidden points, the stopped clock, the round trip, the rearmed cut

    /** The top a hidden ring takes from a BakedLike sample of one layer: its cell's column, an empty one 0. */
    private static int ringTop(Layer layer, int cellX, int cellZ) {
        if (layer == Layer.FINE) return Math.max(0, BakedLike.column(cellX * 4, cellZ * 4).top());
        final List<Sample> fine = new ArrayList<>(16);
        for (int z = 0; z < 16; z += 4) {
            for (int x = 0; x < 16; x += 4) fine.add(BakedLike.column(cellX * 16 + x, cellZ * 16 + z));
        }
        return Math.max(0, FarViewPack.coarsen(2, fine).top());
    }

    /**
     * The hidden points of {@code baked-hidden} and {@code counter-hidden} on the BakedLike packs, which hide
     * nothing: stone of plains at the lowest top of the ring around the cells of their area on their own layer,
     * walked side by side as the baker walks it, the fine ring of the area across the tile edge read from the
     * fine layer of tile (-1, 0) that only the whole pack on disk has.
     */
    @Test
    public void theHiddenPointsTakeTheLowestTopOfTheRingOfTheirArea(@TempDir Path dir) throws IOException {
        final FarViewShardFixture.Content baked = BakedLike.content(dir);
        assertTrue(reader(baked.packs().get("-1 0")).coarseOnly(), "tile (-1, 0) is served by its coarse prefix");
        assertFalse(reader(baked.full().get("-1 0")).coarseOnly(), "and whole on disk");
        for (String mode : List.of("baked-hidden", "counter-hidden")) {
            final JsonObject expect = JsonParser.parseString(FarViewShardFixture.expectJson(mode, baked, BakedLike.SPAWN, -1))
                    .getAsJsonObject();
            final Map<String, JsonObject> points = new TreeMap<>();
            for (JsonElement point : expect.getAsJsonArray("points")) {
                points.put(point.getAsJsonObject().get("name").getAsString(), point.getAsJsonObject());
            }
            assertEquals(new TreeSet<>(List.of("hidden-edge-coarse", "hidden-edge-fine", "hidden-peak", "mountain", "peak", "water")),
                    points.keySet(), mode);
            for (FarViewShardFixture.HiddenPoint hidden : FarViewShardFixture.HIDDEN_POINTS) {
                final int level = hidden.layer() == Layer.COARSE ? FarViewPack.COARSE_LEVEL : 2;
                final var area = FarViewShardFixture.HIDDEN_AREAS.stream().filter(a -> hidden.x() >= a.x1() && hidden.x() <= a.x2()
                        && hidden.z() >= a.z1() && hidden.z() <= a.z2()).findFirst().orElseThrow();
                final int minX = area.x1() >> level, maxX = area.x2() >> level, minZ = area.z1() >> level, maxZ = area.z2() >> level;
                int lowest = Integer.MAX_VALUE;
                for (int x = minX - 1; x <= maxX + 1; x++) {
                    lowest = Math.min(lowest, Math.min(ringTop(hidden.layer(), x, minZ - 1), ringTop(hidden.layer(), x, maxZ + 1)));
                }
                for (int z = minZ; z <= maxZ; z++) {
                    lowest = Math.min(lowest, Math.min(ringTop(hidden.layer(), minX - 1, z), ringTop(hidden.layer(), maxX + 1, z)));
                }
                final JsonObject point = points.get(hidden.name());
                assertEquals(lowest, point.get("y").getAsInt(), mode + " " + hidden);
                assertEquals("minecraft:stone", point.get("block").getAsString());
                assertEquals(Block.STONE.stateId(), point.get("state").getAsInt());
                assertEquals("minecraft:plains", point.get("biome").getAsString());
                assertEquals(hidden.layer() == Layer.COARSE ? "coarse" : "fine", point.get("layer").getAsString());
            }
        }
        assertEquals(-1, FarViewShardFixture.HIDDEN_POINTS.get(2).x() >> 12, "the coarse point is in tile (-1, 0)");
        for (JsonElement point : expectBaked(baked).getAsJsonArray("points")) {
            assertFalse(point.getAsJsonObject().has("biome"), "baked has no hidden point: " + point);
        }
    }

    @Test
    public void theDayTimeGoesIntoExpectJsonOnlyWhenGiven(@TempDir Path dir) throws IOException {
        final FarViewShardFixture.Content baked = BakedLike.content(dir);
        assertEquals(6000, JsonParser.parseString(FarViewShardFixture.expectJson("baked", baked, BakedLike.SPAWN, 6000))
                .getAsJsonObject().get("day_time").getAsLong());
        assertFalse(expectBaked(baked).has("day_time"));
    }

    /**
     * §19 「停住的时钟不被对齐，夹具默认停在正午」 (REQ-M3-034): the clock {@link FarViewShardFixture#holdTime} stops, at
     * the day time or at noon without one, stays there against the wall clock the map shard holds its clock to
     * ({@link ShardWorld#alignClock}); with {@code farview.fixture.clock-runs} it is only set, and the wall clock
     * takes it.
     */
    @Test
    public void aStoppedClockStaysAtTheDayTimeAgainstTheWallClock() {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).build());
        final InstanceContainer stopped = process.instance().createInstanceContainer(DimensionType.OVERWORLD);
        FarViewShardFixture.holdTime(stopped, 6000, false);
        final InstanceContainer noon = process.instance().createInstanceContainer(DimensionType.OVERWORLD);
        FarViewShardFixture.holdTime(noon, -1, false);
        final InstanceContainer running = process.instance().createInstanceContainer(DimensionType.OVERWORLD);
        FarViewShardFixture.holdTime(running, 6000, true);
        // 2026-10-07T07:15:23.456Z, 138,469 on the wall clock of a map shard
        for (InstanceContainer instance : List.of(stopped, noon, running)) {
            ShardWorld.alignClock(Objects.requireNonNull(instance.defaultClock()), 1_791_357_323_456L);
        }
        assertEquals(6000, stopped.getTime());
        assertEquals(0f, rate(stopped), "stopped: the client's clock does not move");
        assertEquals(6000, noon.getTime(), "without a day time: noon");
        assertEquals(0f, rate(noon));
        assertEquals(138_469, running.getTime(), "the counter-proof runs on the wall clock");
        assertEquals(1f, rate(running));
    }

    private static float rate(InstanceContainer instance) {
        final SetTimePacket packet = instance.createTimePacket();
        return Objects.requireNonNull(packet.clocks().get(Objects.requireNonNull(instance.defaultClock()).clock())).rate();
    }

    @Test
    public void theProxyHoldsEachWayForHalfTheRoundTrip() throws Exception {
        try (ServerSocket echo = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofPlatform().daemon().start(() -> {
                try (Socket peer = echo.accept()) {
                    final byte[] buffer = new byte[1024];
                    for (int read; (read = peer.getInputStream().read(buffer)) >= 0; ) peer.getOutputStream().write(buffer, 0, read);
                } catch (IOException _) {
                    // the test ended
                }
            });
            final FarViewShardFixture.DelayProxy proxy = new FarViewShardFixture.DelayProxy(0, echo.getLocalPort(), 300);
            proxy.start();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                final long started = System.nanoTime();
                client.getOutputStream().write(new byte[]{1, 2, 3});
                final byte[] back = client.getInputStream().readNBytes(3);
                final long millis = (System.nanoTime() - started) / 1_000_000;
                assertArrayEquals(new byte[]{1, 2, 3}, back);
                assertTrue(millis >= 300 && millis < 1300, "one round trip of 300 ms: " + millis + " ms");
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    public void rearmLetsHalfCutItsNextResponseAgain() throws Exception {
        final byte[] object = new byte[200_000];
        new Random(13).nextBytes(object);
        final String hex = SynxDigests.hex(SynxDigests.sha256(object));
        final FarViewShardFixture.PackServer server = new FarViewShardFixture.PackServer("farview", Map.of(hex, object),
                true, "{}\n");
        server.start(0);
        try {
            final String path = "/farview/farview/" + hex + ".hfp";
            assertThrows(IOException.class, () -> get(server.port(), path, null));
            assertEquals(200, get(server.port(), path, null).statusCode(), "cut once");
            assertEquals("rearmed\n", new String(get(server.port(), "/rearm", null).body(), StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> get(server.port(), path, null), "and again after /rearm");
            assertEquals(List.of("GET " + path + " - 200 cut", "GET " + path + " - 200", "GET " + path + " - 200 cut"), server.log());
        } finally {
            server.stop();
        }
    }
}
