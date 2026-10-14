package io.github.bearisbug.synaxis.game.client.farview;

import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement.Tile;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the client makes of an announcement before it downloads anything ({@code docs/DESIGN-M3.md}
 * {@code API-M3-015}, M3-S {@code REQ-M3-032}): the sum of its objects against the cache limit, one
 * host for all of them, local addresses only for a local server, and the {@code .part} files it
 * finds. The §19 scenarios {@code 宣告合计超过缓存上限时不下载}, {@code 宣告里的主机不一致或解析到本地地址时不下载}
 * and {@code 收到宣告与客户端启动时清理残留的 .part}.
 * <p>
 * The downloader goes through the test's HTTP server as its proxy, so a request for any host,
 * {@code 192.168.1.1} included, lands there and is recorded with the host it was for.
 */
class FarViewClientTest {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-");
    private static final InetAddress REMOTE = address("203.0.113.5");
    private static final InetAddress LOCAL = address("127.0.0.1");

    @TempDir
    Path dir;

    private HttpServer server;
    /** {@code host path range} of every request, as the proxy got it. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try (exchange) {
                final URI uri = exchange.getRequestURI();
                final String range = exchange.getRequestHeaders().getFirst("Range");
                requests.add(uri.getHost() + " " + uri.getPath() + " " + (range == null ? "-" : range));
                final byte[] object = objects.get(uri.getPath());
                if (object == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                final Matcher from = range == null ? null : RANGE.matcher(range);
                if (from != null && from.matches()) {
                    final int start = Integer.parseInt(from.group(1));
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (object.length - 1) + "/" + object.length);
                    exchange.sendResponseHeaders(206, object.length - start);
                    exchange.getResponseBody().write(object, start, object.length - start);
                } else {
                    exchange.sendResponseHeaders(200, object.length);
                    exchange.getResponseBody().write(object);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private FarViewDownloader downloader(FarViewCache cache) {
        return new FarViewDownloader(cache, FarViewDownloader.http().proxy(ProxySelector.of(server.getAddress())).build(),
                List.of(Duration.ofMillis(10), Duration.ofMillis(20)), Duration.ofSeconds(5));
    }

    private FarViewConnection announced(FarViewAnnouncement announcement, FarViewCache cache, InetAddress serverAddress) {
        return FarViewClient.announced(announcement, System.nanoTime(), FarViewClient.Gate.SUPPORTED,
                new FarViewSettings(FarViewSettings.DEFAULT_RADIUS), cache, downloader(cache), serverAddress);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The object of tile {@code tileX}, 0: tile 0 a whole pack, the others a coarse prefix, each block of
     * random samples, some kilobytes.
     */
    private static byte[] object(int tileX) {
        final Random random = new Random(tileX);
        final FarViewPack.Writer writer = new FarViewPack.Writer(2, tileX, 0, -64, 256, 32366, 776, List.of("minecraft:plains"));
        for (FarViewPack.Layer layer : tileX == 0 ? FarViewPack.Layer.values() : new FarViewPack.Layer[]{FarViewPack.Layer.COARSE}) {
            final List<FarViewPack.Sample> samples = new ArrayList<>(FarViewPack.BLOCK_SAMPLES);
            for (int i = 0; i < FarViewPack.BLOCK_SAMPLES; i++) {
                samples.add(new FarViewPack.Sample(1 + random.nextInt(30_000), 0, random.nextInt(200), 0, 0, 0));
            }
            writer.block(layer, 0, 0, samples);
        }
        return tileX == 0 ? writer.write() : writer.coarsePrefix();
    }

    private static int layer(int tileX) {
        return tileX == 0 ? FarViewAnnouncement.LAYER_FULL : FarViewAnnouncement.LAYER_COARSE;
    }

    /** Serves {@code object} under its digest and returns its tile, announced at {@code root}. */
    private Tile serve(int tileX, byte[] object, String root) {
        final String hex = HexFormat.of().formatHex(sha256(object));
        objects.put("/m/farview/" + hex + ".hfp", object);
        return new Tile(tileX, 0, layer(tileX), object.length, sha256(object), root + "/m/farview/" + hex + ".hfp");
    }

    private static FarViewAnnouncement announcement(List<Tile> tiles) {
        return new FarViewAnnouncement("b1", 2, 4, -64, 256, tiles);
    }

    private String root() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void anAnnouncementOverTheCacheLimitIsTakenAsNone() {
        final FarViewCache cache = new FarViewCache(dir);
        final List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            final byte[] digest = new byte[FarViewAnnouncement.SHA256_BYTES];
            digest[0] = (byte) (i + 1);
            tiles.add(new Tile(i - 16, 0, layer(i - 16), FarViewAnnouncement.MAX_OBJECT_BYTES, digest,
                    root() + "/m/farview/" + i + ".hfp"));
        }
        assertEquals(1_342_177_280L, tiles.stream().mapToLong(Tile::bytes).sum());

        try (LogLines log = new LogLines()) {
            final FarViewConnection c = announced(announcement(tiles), cache, LOCAL);
            assertFalse(c.available(), "taken as no announcement");
            assertEquals(cache.library(FarViewClient.NO_FAR_VIEW), FarViewClient.library(cache, c, true), "pinned to dh/off");
            c.begin(Runnable::run);
            assertEquals(List.of(), requests, "nothing is downloaded");
            assertTrue(log.warnings().stream().anyMatch(line -> line.contains(
                    "Far view announcement of 1342177280 bytes is over the cache limit of 1073741824 bytes: ignored")), log.warnings().toString());
        }
    }

    @Test
    void anAnnouncementNamingTwoHostsIsTakenAsNone() {
        final FarViewCache cache = new FarViewCache(dir);
        for (List<String> roots : List.of(List.of("http://a.test", "http://b.test"), List.of("http://a.test", "http://a.test:8080"),
                List.of("http://a.test", "https://a.test"))) {
            final List<Tile> tiles = List.of(serve(0, object(0), roots.get(0)), serve(1, object(1), roots.get(1)));
            try (LogLines log = new LogLines()) {
                final FarViewConnection c = announced(announcement(tiles), cache, REMOTE);
                assertFalse(c.available(), roots.toString());
                assertEquals(cache.library(FarViewClient.NO_FAR_VIEW), FarViewClient.library(cache, c, true), roots.toString());
                c.begin(Runnable::run);
                assertTrue(log.warnings().stream().anyMatch(line -> line.contains("Far view announcement names more than one host: ignored")),
                        roots + " " + log.warnings());
            }
        }
        assertEquals(List.of(), requests);
    }

    @Test
    void aHostResolvingToALocalAddressIsAskedOnlyWhenTheServerIsLocalToo() throws IOException {
        for (InetAddress serverAddress : List.of(REMOTE, LOCAL)) {
            for (String root : List.of("http://192.168.1.1", root())) {
                final String host = URI.create(root).getHost();
                final FarViewCache cache = new FarViewCache(Files.createTempDirectory(dir, "cache"));
                final List<Tile> tiles = List.of(serve(0, object(0), root), serve(1, object(1), root));
                requests.clear();
                try (LogLines log = new LogLines()) {
                    final FarViewConnection c = announced(announcement(tiles), cache, serverAddress);
                    assertTrue(c.available(), "one host: the announcement holds");
                    c.begin(Runnable::run);
                    final List<String> asked = requests.stream().filter(line -> line.startsWith(host + " ")).toList();
                    if (serverAddress == REMOTE) {
                        assertEquals(List.of(), asked, root + " for the server " + serverAddress);
                        assertNotNull(c.failure(), "the far view is off");
                        assertTrue(log.warnings().stream().anyMatch(line -> line.contains("Far view off: " + host + " resolves to the local address ")
                                && line.contains(", the server " + serverAddress.getHostAddress() + " is not local")), log.warnings().toString());
                        assertEquals(tiles, cache.missing(tiles), "nothing downloaded");
                    } else {
                        assertEquals(2, asked.size(), root + " for the server " + serverAddress + ": " + requests);
                        assertEquals(List.of(), cache.missing(tiles), "both downloaded");
                    }
                }
            }
        }
    }

    @Test
    void partsNotNamedOrOlderThanADayGoWhenAnAnnouncementArrives() throws IOException {
        final byte[] b = object(0), c = object(1), d = object(2);
        assertTrue(c.length > 1000, "C is longer than its part: " + c.length);
        final List<Tile> tiles = List.of(serve(0, b, root()), serve(1, c, root()), serve(2, d, root()));
        final String hexA = "a".repeat(64), hexB = FarViewCache.hex(tiles.get(0)), hexC = FarViewCache.hex(tiles.get(1)),
                hexD = FarViewCache.hex(tiles.get(2));
        final long now = System.currentTimeMillis();
        final FileTime dayAndAHourAgo = FileTime.fromMillis(now - Duration.ofHours(25).toMillis());
        final FileTime anHourAgo = FileTime.fromMillis(now - Duration.ofHours(1).toMillis());

        // The first cache has every object: nothing is downloaded, the parts not named or old go at once, C at the clean
        final FarViewCache one = new FarViewCache(dir.resolve("one"));
        Files.createDirectories(one.pack("x").getParent());
        Files.write(one.pack(hexB), b);
        Files.write(one.pack(hexC), c);
        Files.write(one.pack(hexD), d);
        Files.write(one.part(hexA), new byte[500]);
        Files.write(one.part(hexB), new byte[500]);
        Files.setLastModifiedTime(one.part(hexB), dayAndAHourAgo);
        Files.write(one.part(hexC), Arrays.copyOf(c, 1000));
        Files.setLastModifiedTime(one.part(hexC), anHourAgo);
        final FarViewConnection first = announced(announcement(tiles), one, LOCAL);
        assertEquals(List.of(hexC), parts(one), "A (not named) and B (a day old) go as the announcement arrives");
        first.begin(Runnable::run);
        assertEquals(List.of(), requests, "every object is there");
        final FarViewCache.Cleaned cleaned = one.clean(FarViewCache.LIMIT_BYTES, first.viewKey());
        assertEquals(List.of(), parts(one), "the clean after a bake takes C");
        assertEquals(List.of(hexC), cleaned.parts());

        // The second has none: B and D (longer than announced) from the start, C resumed from its 1,000 bytes
        final FarViewCache two = new FarViewCache(dir.resolve("two"));
        Files.createDirectories(two.pack("x").getParent());
        Files.write(two.part(hexA), new byte[500]);
        Files.write(two.part(hexB), new byte[500]);
        Files.setLastModifiedTime(two.part(hexB), dayAndAHourAgo);
        Files.write(two.part(hexC), Arrays.copyOf(c, 1000));
        Files.setLastModifiedTime(two.part(hexC), anHourAgo);
        Files.write(two.part(hexD), new byte[d.length + 10]);
        final FarViewConnection second = announced(announcement(tiles), two, LOCAL);
        assertEquals(List.of(hexC, hexD).stream().sorted().toList(), parts(two), "A and B go as the announcement arrives");
        second.begin(Runnable::run);
        final Map<String, String> asked = new TreeMap<>();
        for (String line : requests) asked.put(line.split(" ")[1], line.split(" ")[2]);
        assertEquals(Map.of("/m/farview/" + hexB + ".hfp", "-", "/m/farview/" + hexC + ".hfp", "bytes=1000-",
                "/m/farview/" + hexD + ".hfp", "-"), asked, requests.toString());
        assertEquals(List.of(), two.missing(tiles));
        assertArrayEquals(c, Files.readAllBytes(two.pack(hexC)));
        assertArrayEquals(d, Files.readAllBytes(two.pack(hexD)));
        two.clean(FarViewCache.LIMIT_BYTES, second.viewKey());
        assertEquals(List.of(), parts(two));
        assertNotEquals("", second.viewKey());
    }

    /** The digests of the {@code .part} files in a cache, in order. */
    private static List<String> parts(FarViewCache cache) throws IOException {
        try (Stream<Path> files = Files.list(cache.pack("x").getParent())) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".hfp.part"))
                    .map(name -> name.substring(0, name.length() - ".hfp.part".length())).sorted().toList();
        }
    }
}
