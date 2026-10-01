package io.github.bearisbug.synaxis.game.client.farview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement.Tile;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The downloads of {@code docs/DESIGN-M3.md} {@code API-M3-015} (下载条) against an HTTP server of the
 * test's own, with the frozen packs as the objects: the §19 {@code REQ-M3-025} scenarios
 * {@code 下载中断后断点续传} and {@code 包校验失败时重试两次后放弃}, and the size, layer and
 * concurrency rules around them.
 */
class FarViewDownloaderTest {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-");

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger mostOpen = new AtomicInteger();
    /** How the server answers request number n (from 1) for an object. */
    private volatile Answer answer = (n, range, object, out) -> out.whole(object);

    private interface Answer {
        void answer(int n, Integer range, byte[] object, Out out) throws IOException;
    }

    /** What an answer can do with the exchange. */
    private record Out(HttpExchange exchange) {
        void whole(byte[] body) throws IOException {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }

        void from(byte[] object, int from) throws IOException {
            exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (object.length - 1) + "/" + object.length);
            exchange.sendResponseHeaders(206, object.length - from);
            exchange.getResponseBody().write(object, from, object.length - from);
        }

        void cut(byte[] object, int upTo) throws IOException {
            exchange.sendResponseHeaders(200, object.length);
            final OutputStream body = exchange.getResponseBody();
            body.write(object, 0, upTo);
            body.flush();
            throw new IOException("cut after " + upTo + " bytes");
        }
    }

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try (exchange) {
                final int now = open.incrementAndGet();
                mostOpen.accumulateAndGet(now, Math::max);
                try {
                    final String path = exchange.getRequestURI().getPath();
                    final String range = exchange.getRequestHeaders().getFirst("Range");
                    requests.add(path + " " + (range == null ? "-" : range));
                    final byte[] object = objects.get(path);
                    if (object == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    Thread.sleep(20);
                    final Matcher matcher = range == null ? null : RANGE.matcher(range);
                    final Integer from = matcher != null && matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
                    answer.answer(counts.computeIfAbsent(path, _ -> new AtomicInteger()).incrementAndGet(), from, object,
                            new Out(exchange));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    open.decrementAndGet();
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] vector(String name) throws IOException {
        return Files.readAllBytes(Path.of(System.getProperty("synaxis.farview-vectors.dir")).resolve(name));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves {@code bytes} and announces them as {@code announced} bytes with the digest of {@code digestOf}. */
    private Tile serve(String name, byte[] bytes, int layer, long announced, byte[] digestOf) {
        objects.put("/m/farview/" + name, bytes);
        return new Tile(-1, 0, layer, announced, sha256(digestOf), "http://127.0.0.1:" + server.getAddress().getPort()
                + "/m/farview/" + name);
    }

    private Tile serve(String name, byte[] bytes, int layer) {
        return serve(name, bytes, layer, bytes.length, bytes);
    }

    private FarViewDownloader downloader(FarViewCache cache) {
        return new FarViewDownloader(cache, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build(),
                List.of(Duration.ofMillis(10), Duration.ofMillis(20)), Duration.ofSeconds(5));
    }

    private FarViewDownloader.Result download(FarViewCache cache, List<Tile> tiles) {
        return downloader(cache).download(tiles, new AtomicBoolean(), _ -> {
        });
    }

    @Test
    void theDesignedTimeoutsWaitsAndConcurrency() {
        assertEquals(3, FarViewDownloader.ATTEMPTS);
        assertEquals(2, FarViewDownloader.CONCURRENCY);
        assertEquals(Duration.ofSeconds(10), FarViewDownloader.CONNECT_TIMEOUT);
        assertEquals(Duration.ofSeconds(30), FarViewDownloader.IDLE_TIMEOUT);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(4)), FarViewDownloader.RETRY_WAITS);
    }

    @Test
    void aWholePackAndACoarsePrefixLandInTheCacheUnderTheirDigests() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        final byte[] coarse = vector("pack-small.coarse.hfp");
        final Tile wholeTile = serve("whole", whole, FarViewAnnouncement.LAYER_FULL);
        final Tile coarseTile = serve("coarse", coarse, FarViewAnnouncement.LAYER_COARSE);
        final AtomicLong progress = new AtomicLong();

        final FarViewDownloader.Result result = downloader(cache).download(List.of(wholeTile, coarseTile), new AtomicBoolean(),
                progress::set);

        assertTrue(result.success(), result.toString());
        assertEquals(2, result.objects());
        assertEquals(whole.length + coarse.length, result.bytes());
        assertEquals(whole.length + coarse.length, progress.get());
        assertArrayEquals(whole, Files.readAllBytes(cache.pack(FarViewConnection.hex(wholeTile))));
        assertArrayEquals(coarse, Files.readAllBytes(cache.pack(FarViewConnection.hex(coarseTile))));
        assertFalse(Files.exists(cache.part(FarViewConnection.hex(wholeTile))));
        assertEquals(List.of(), cache.missing(List.of(wholeTile, coarseTile)));
    }

    @Test
    void aCutDownloadResumesWithRangeFromWhatItHas() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        final int half = whole.length / 2;
        answer = (n, from, object, out) -> {
            if (n == 1) out.cut(object, half);
            else if (from != null) out.from(object, from);
            else out.whole(object);
        };
        final Tile tile = serve("whole", whole, FarViewAnnouncement.LAYER_FULL);

        final FarViewDownloader.Result result = download(cache, List.of(tile));

        assertTrue(result.success(), result.toString());
        assertEquals(2, requests.size(), requests.toString());
        assertEquals("/m/farview/whole -", requests.get(0));
        final Matcher second = RANGE.matcher(requests.get(1).substring(requests.get(1).indexOf(' ') + 1));
        assertTrue(second.matches(), "the second request asks for the rest: " + requests.get(1));
        final int resumedAt = Integer.parseInt(second.group(1));
        assertTrue(resumedAt > 0 && resumedAt <= half, "resumed at " + resumedAt + " of the " + half + " bytes sent");
        assertArrayEquals(whole, Files.readAllBytes(cache.pack(FarViewConnection.hex(tile))));
    }

    @Test
    void aServerWithoutRangeIsDownloadedAgainFromTheStart() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        answer = (n, _, object, out) -> {
            if (n == 1) out.cut(object, object.length / 2);
            else out.whole(object);
        };
        final Tile tile = serve("whole", whole, FarViewAnnouncement.LAYER_FULL);

        final FarViewDownloader.Result result = download(cache, List.of(tile));

        assertTrue(result.success(), result.toString());
        assertEquals(2, requests.size(), requests.toString());
        assertArrayEquals(whole, Files.readAllBytes(cache.pack(FarViewConnection.hex(tile))));
    }

    @Test
    void aPartLeftByAnEarlierConnectionIsResumed() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        answer = (_, from, object, out) -> {
            if (from != null) out.from(object, from);
            else out.whole(object);
        };
        final Tile tile = serve("whole", whole, FarViewAnnouncement.LAYER_FULL);
        Files.createDirectories(cache.part(FarViewConnection.hex(tile)).getParent());
        Files.write(cache.part(FarViewConnection.hex(tile)), Arrays.copyOf(whole, 1000));

        assertTrue(download(cache, List.of(tile)).success());
        assertEquals(List.of("/m/farview/whole bytes=1000-"), requests);
        assertArrayEquals(whole, Files.readAllBytes(cache.pack(FarViewConnection.hex(tile))));
    }

    @Test
    void anObjectThatNeverMatchesItsDigestIsRequestedThreeTimesAndLeavesNothing() {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole;
        try {
            whole = vector("pack-small.hfp");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        final byte[] broken = whole.clone();
        broken[broken.length / 2] ^= 0x01;
        final Tile tile = serve("broken", broken, FarViewAnnouncement.LAYER_FULL, whole.length, whole);

        final FarViewDownloader.Result result = download(cache, List.of(tile));

        assertFalse(result.success());
        assertEquals(FarViewConnection.hex(tile), result.failed());
        assertTrue(result.reason().contains("SHA-256"), result.reason());
        assertEquals(3, requests.size(), requests.toString());
        assertFalse(Files.exists(cache.pack(FarViewConnection.hex(tile))));
        assertFalse(Files.exists(cache.part(FarViewConnection.hex(tile))));
    }

    @Test
    void moreBytesThanAnnouncedIsAFailedAttempt() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        final byte[] longer = Arrays.copyOf(whole, whole.length + 100);
        final Tile tile = serve("longer", longer, FarViewAnnouncement.LAYER_FULL, whole.length, whole);

        final FarViewDownloader.Result result = download(cache, List.of(tile));

        assertFalse(result.success());
        assertTrue(result.reason().contains("more"), result.reason());
        assertEquals(3, requests.size(), requests.toString());
        assertFalse(Files.exists(cache.part(FarViewConnection.hex(tile))));
    }

    @Test
    void aWholePackAnnouncedAsCoarseIsNotTaken() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final Tile tile = serve("whole", vector("pack-small.hfp"), FarViewAnnouncement.LAYER_COARSE);
        final FarViewDownloader.Result result = download(cache, List.of(tile));
        assertFalse(result.success());
        assertTrue(result.reason().contains("layer"), result.reason());
    }

    @Test
    void aCoarsePrefixAnnouncedAsWholeIsNotTaken() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final Tile tile = serve("coarse", vector("pack-small.coarse.hfp"), FarViewAnnouncement.LAYER_FULL);
        final FarViewDownloader.Result result = download(cache, List.of(tile));
        assertFalse(result.success());
        assertTrue(result.reason().contains("layer"), result.reason());
    }

    @Test
    void bytesOfTheRightDigestThatAreNoPackAreNotTaken() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] notAPack = new byte[512];
        final Tile tile = serve("blob", notAPack, FarViewAnnouncement.LAYER_FULL);
        final FarViewDownloader.Result result = download(cache, List.of(tile));
        assertFalse(result.success());
        assertTrue(result.reason().contains("magic"), result.reason());
        assertEquals(3, requests.size());
    }

    @Test
    void aMissingObjectFailsAndStopsTheOthers() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final Tile good = serve("whole", vector("pack-small.hfp"), FarViewAnnouncement.LAYER_FULL);
        final Tile gone = new Tile(0, 0, 0, 10, sha256(new byte[]{1}), "http://127.0.0.1:" + server.getAddress().getPort()
                + "/m/farview/gone");
        final FarViewDownloader.Result result = download(cache, List.of(gone, good));
        assertFalse(result.success());
        assertEquals(FarViewConnection.hex(gone), result.failed());
        assertTrue(result.reason().contains("404"), result.reason());
    }

    @Test
    void atMostTwoObjectsDownloadAtOnce() {
        final FarViewCache cache = new FarViewCache(dir);
        final List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // six coarse prefixes of six tiles: six objects, six digests
            final byte[] prefix = new FarViewPack.Writer(2, i, 0, -64, 256, 32366, 776, List.of("minecraft:plains"))
                    .block(FarViewPack.Layer.COARSE, 0, 0, Collections.nCopies(FarViewPack.BLOCK_SAMPLES,
                            new FarViewPack.Sample(1, 0, 70 + i, 0, 0, 0)))
                    .coarsePrefix();
            objects.put("/m/farview/c" + i, prefix);
            tiles.add(new Tile(i, 0, FarViewAnnouncement.LAYER_COARSE, prefix.length, sha256(prefix),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/m/farview/c" + i));
        }
        final FarViewDownloader.Result result = download(cache, tiles);
        assertTrue(result.success(), result.toString());
        assertEquals(6, result.objects());
        assertEquals(6, requests.size());
        assertTrue(mostOpen.get() <= 2, "at most two at once, saw " + mostOpen.get());
        assertEquals(List.of(), cache.missing(tiles));
    }

    @Test
    void aPackOfAnotherTileThanAnnouncedIsNotTaken() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        objects.put("/m/farview/elsewhere", whole);
        final Tile tile = new Tile(5, 5, FarViewAnnouncement.LAYER_FULL, whole.length, sha256(whole),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/m/farview/elsewhere");
        final FarViewDownloader.Result result = download(cache, List.of(tile));
        assertFalse(result.success());
        assertTrue(result.reason().contains("tile"), result.reason());
    }

    @Test
    void aServerThatSendsNoResponseFailsOnTheIdleClockAndItsLateAnswersAreDropped() throws IOException, InterruptedException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        final AtomicInteger late = new AtomicInteger();
        answer = (_, _, object, out) -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            late.incrementAndGet();
            out.whole(object);
        };
        final Tile tile = serve("whole", whole, FarViewAnnouncement.LAYER_FULL);
        final FarViewDownloader downloader = new FarViewDownloader(cache, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build(),
                List.of(Duration.ofMillis(10), Duration.ofMillis(20)), Duration.ofMillis(700));
        final FarViewDownloader.Result result = downloader.download(List.of(tile), new AtomicBoolean(), _ -> {
        });
        assertFalse(result.success());
        assertTrue(result.reason().startsWith("no byte for"), "waiting for the response is waiting for a byte: " + result.reason());
        assertEquals(3, requests.size());
        final long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (late.get() < 3 && System.nanoTime() < until) Thread.sleep(50);
        Thread.sleep(300);
        assertFalse(Files.exists(cache.part(FarViewConnection.hex(tile))), "an attempt given up on writes nothing later");
        assertFalse(Files.exists(cache.pack(FarViewConnection.hex(tile))));
    }

    @Test
    void aCancelStopsAndKeepsThePart() throws IOException, InterruptedException {
        final FarViewCache cache = new FarViewCache(dir);
        final byte[] whole = vector("pack-small.hfp");
        final AtomicBoolean cancel = new AtomicBoolean();
        answer = (_, _, object, out) -> {
            out.exchange().sendResponseHeaders(200, object.length);
            final OutputStream body = out.exchange().getResponseBody();
            body.write(object, 0, 1000);
            body.flush();
            cancel.set(true);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        final Tile tile = serve("whole", whole, FarViewAnnouncement.LAYER_FULL);
        final long started = System.nanoTime();
        final FarViewDownloader.Result result = downloader(cache).download(List.of(tile), cancel, _ -> {
        });
        assertFalse(result.success());
        assertNull(result.failed());
        assertEquals("cancelled", result.reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2500, "it stopped before the server finished");
        assertTrue(Files.exists(cache.part(FarViewConnection.hex(tile))), "the .part stays for the next connection");
        assertEquals(1, requests.size());
    }
}
