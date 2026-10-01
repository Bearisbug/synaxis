package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link FarViewShardFixture} serves, without a game port: the synthetic packs and their
 * announcement, the points of {@code /expect.json}, and the HTTP side ({@code Range}, the shared
 * rate, {@code half} and {@code break-sha}). The container cases of track C stand on these.
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
                new Pos(264.5, 64, 264.5))).getAsJsonObject();
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
}
