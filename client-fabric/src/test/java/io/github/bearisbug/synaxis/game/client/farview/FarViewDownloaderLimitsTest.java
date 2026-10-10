package io.github.bearisbug.synaxis.game.client.farview;

import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10 (surface 2, S2-02 and S2-V01) as a regression test: an announced object is
 * at most 40 MiB, so no {@code .part} the downloader reads back grows with what a server announces;
 * a {@code .part} longer than its object is dropped and the object downloaded from the start; and a
 * cleaning takes every {@code .part}, so one left over the cache limit does not stay there
 * ({@code docs/DESIGN-M3.md} {@code REQ-M3-032}). Before the fix a 3 GiB {@code .part} ended the
 * download in an {@link OutOfMemoryError} and stayed through {@link FarViewCache#clean}.
 */
class FarViewDownloaderLimitsTest {

    private static final long THREE_GIB = 3L << 30;

    @TempDir
    Path dir;

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sparse(Path part, long size) throws IOException {
        Files.createDirectories(part.getParent());
        try (RandomAccessFile file = new RandomAccessFile(part.toFile(), "rw")) {
            file.setLength(size);
        }
    }

    @Test
    void anObjectOverFortyMebibytesIsNoTile() {
        final byte[] digest = new byte[FarViewAnnouncement.SHA256_BYTES];
        final String url = "http://127.0.0.1:9/never-asked.hfp";
        new FarViewAnnouncement.Tile(0, 0, FarViewAnnouncement.LAYER_FULL, FarViewAnnouncement.MAX_OBJECT_BYTES, digest, url);
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement.Tile(0, 0, FarViewAnnouncement.LAYER_FULL,
                FarViewAnnouncement.MAX_OBJECT_BYTES + 1, digest, url));
        assertThrows(MalformedMessageException.class, () -> new FarViewAnnouncement.Tile(0, 0, FarViewAnnouncement.LAYER_FULL,
                THREE_GIB, digest, url));
    }

    @Test
    void aPartLongerThanItsObjectIsDroppedAndTheObjectDownloadedFromTheStart() throws IOException {
        final FarViewCache cache = new FarViewCache(dir.resolve("farview"));
        final byte[] whole = Files.readAllBytes(Path.of(System.getProperty("synaxis.farview-vectors.dir")).resolve("pack-small.hfp"));
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                final String range = exchange.getRequestHeaders().getFirst("Range");
                requests.add(exchange.getRequestURI().getPath() + " " + (range == null ? "-" : range));
                exchange.sendResponseHeaders(200, whole.length);
                exchange.getResponseBody().write(whole);
            }
        });
        server.start();
        try {
            final FarViewAnnouncement.Tile tile = new FarViewAnnouncement.Tile(-1, 0, FarViewAnnouncement.LAYER_FULL, whole.length,
                    sha256(whole), "http://127.0.0.1:" + server.getAddress().getPort() + "/m/farview/whole.hfp");
            final Path part = cache.part(HexFormat.of().formatHex(tile.sha256()));
            sparse(part, THREE_GIB);
            final FarViewDownloader downloader = new FarViewDownloader(cache, HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                    .build(), List.of(Duration.ZERO, Duration.ZERO), Duration.ofSeconds(5));

            final FarViewDownloader.Result result = downloader.download(List.of(tile), new AtomicBoolean(), _ -> {
            });

            assertTrue(result.success(), result.toString());
            assertEquals(List.of("/m/farview/whole.hfp -"), requests, "from the start, without Range");
            assertFalse(Files.exists(part));
            assertArrayEquals(whole, Files.readAllBytes(cache.pack(HexFormat.of().formatHex(tile.sha256()))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aCleaningTakesAPartOverTheCacheLimit() throws IOException {
        final FarViewCache cache = new FarViewCache(dir.resolve("farview"));
        final byte[] digest = new byte[FarViewAnnouncement.SHA256_BYTES];
        digest[0] = 1;
        final String hex = HexFormat.of().formatHex(digest);
        final Path part = cache.part(hex);
        sparse(part, THREE_GIB);
        assertTrue(cache.size() > FarViewCache.LIMIT_BYTES, "the cache is over its limit");

        final FarViewCache.Cleaned cleaned = cache.clean(FarViewCache.LIMIT_BYTES, "none");

        assertFalse(Files.exists(part), "the cleaning takes the .part");
        assertEquals(List.of(hex), cleaned.parts());
        assertTrue(cleaned.size() <= FarViewCache.LIMIT_BYTES, "back under the limit: " + cleaned.size());
    }
}
