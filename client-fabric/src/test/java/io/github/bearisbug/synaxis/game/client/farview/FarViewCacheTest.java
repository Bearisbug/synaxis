package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.client.farview.FarViewCache.Marker;
import io.github.bearisbug.synaxis.game.client.farview.FarViewCache.MarkerTile;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement.Tile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The far view cache of {@code docs/DESIGN-M3.md} {@code API-M3-015} (缓存条): the view key, the
 * completion marker, and holding the cache to its limit, with the §19 {@code REQ-M3-025} scenarios
 * {@code 相邻瓦片的粗层变了就换一个库} and {@code 远景缓存超过上限时按最久未用删除}.
 */
class FarViewCacheTest {

    private static final String DH = "3.3.2";

    @TempDir
    Path dir;

    private static byte[] digest(int seed) {
        final byte[] sha = new byte[32];
        Arrays.fill(sha, (byte) seed);
        return sha;
    }

    private static Tile tile(int x, int z, int layer, int seed) {
        return new Tile(x, z, layer, 1000 + seed, digest(seed), "http://cdn.test/m/farview/" + seed + ".hfp");
    }

    /** The nine tiles of an aligned region: (0, 0) whole, the eight around it coarse. */
    private static List<Tile> nine(int changedSeedAt) {
        final List<Tile> tiles = new ArrayList<>();
        int seed = 1;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                final int layer = x == 0 && z == 0 ? FarViewAnnouncement.LAYER_FULL : FarViewAnnouncement.LAYER_COARSE;
                tiles.add(tile(x, z, layer, seed == changedSeedAt ? 99 : seed));
                seed++;
            }
        }
        return tiles;
    }

    @Test
    void theViewKeyIsTheFirstSixteenHexDigitsOfTheDesignedText() throws NoSuchAlgorithmException {
        final List<Tile> tiles = List.of(tile(-1, 0, 1, 7), tile(0, 0, 0, 8));
        final String text = "synaxis-farview 1\n3.3.2\n1\n"
                + "-1 0 1 " + HexFormat.of().formatHex(digest(7)) + "\n"
                + "0 0 0 " + HexFormat.of().formatHex(digest(8)) + "\n";
        final String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        assertEquals(expected, FarViewCache.viewKey(DH, 1, tiles));
        assertTrue(expected.matches("[0-9a-f]{16}"));
    }

    @Test
    void aCoarseTileThatChangedIsAnotherLibrary() {
        // seed 2 is tile (-1, 0), a coarse one
        assertNotEquals(FarViewCache.viewKey(DH, 1, nine(-1)), FarViewCache.viewKey(DH, 1, nine(2)));
    }

    @Test
    void aWholeTileThatChangedIsAnotherLibrary() {
        // seed 5 is tile (0, 0), the whole one
        assertNotEquals(FarViewCache.viewKey(DH, 1, nine(-1)), FarViewCache.viewKey(DH, 1, nine(5)));
    }

    @Test
    void theSameTileAsAnotherLayerIsAnotherLibrary() {
        assertNotEquals(FarViewCache.viewKey(DH, 1, List.of(tile(0, 0, 0, 3))), FarViewCache.viewKey(DH, 1, List.of(tile(0, 0, 1, 3))));
    }

    @Test
    void twoReleasesWithTheSameTilesShareALibrary() {
        final FarViewAnnouncement first = new FarViewAnnouncement("build-a", 2, 4, -64, 256, nine(-1));
        final FarViewAnnouncement second = new FarViewAnnouncement("build-b", 2, 4, -64, 256, nine(-1));
        assertEquals(FarViewCache.viewKey(DH, 1, first.tiles()), FarViewCache.viewKey(DH, 1, second.tiles()));
    }

    @Test
    void anotherDistantHorizonsOrBakeCodeIsAnotherLibrary() {
        final String key = FarViewCache.viewKey(DH, 1, nine(-1));
        assertNotEquals(key, FarViewCache.viewKey("3.3.3", 1, nine(-1)));
        assertNotEquals(key, FarViewCache.viewKey(DH, 2, nine(-1)));
    }

    private Marker marker(String key, List<Tile> tiles, long sources, long lastUsed) {
        return new Marker(FarViewCache.MARKER_FORMAT, key, DH, 1, tiles.stream().map(MarkerTile::of).toList(), sources, 1234,
                lastUsed, lastUsed);
    }

    @Test
    void aMarkerIsWrittenWhole() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final List<Tile> tiles = nine(-1);
        final String key = FarViewCache.viewKey(DH, 1, tiles);
        assertEquals(Optional.empty(), cache.marker(key));
        final Marker written = marker(key, tiles, 2304, 77);
        cache.writeMarker(written);
        assertEquals(Optional.of(written), cache.marker(key));
        try (Stream<Path> files = Files.list(cache.library(key))) {
            assertEquals(List.of(FarViewCache.MARKER), files.map(path -> path.getFileName().toString()).toList(),
                    "no .tmp is left beside the marker");
        }
        assertTrue(FarViewCache.matches(written, key, DH, 1, tiles));
        assertTrue(Files.readString(cache.library(key).resolve(FarViewCache.MARKER)).contains("\"view_key\""),
                "the marker's fields are the snake case names of API-M3-015");
    }

    @Test
    void aMarkerOfOtherTilesVersionsOrKeyDoesNotMatch() {
        final List<Tile> tiles = nine(-1);
        final String key = FarViewCache.viewKey(DH, 1, tiles);
        final Marker marker = marker(key, tiles, 2304, 1);
        assertFalse(FarViewCache.matches(marker, key, DH, 1, nine(3)));
        assertFalse(FarViewCache.matches(marker, key, "3.3.3", 1, tiles));
        assertFalse(FarViewCache.matches(marker, key, DH, 2, tiles));
        assertFalse(FarViewCache.matches(marker, "0123456789abcdef", DH, 1, tiles));
        assertFalse(FarViewCache.matches(new Marker(2, key, DH, 1, marker.tiles(), 2304, 1, 1, 1), key, DH, 1, tiles));
    }

    @Test
    void anUnreadableMarkerIsNone() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        Files.createDirectories(cache.library("0123456789abcdef"));
        Files.writeString(cache.library("0123456789abcdef").resolve(FarViewCache.MARKER), "{not json");
        assertEquals(Optional.empty(), cache.marker("0123456789abcdef"));
    }

    @Test
    void touchMovesLastUsedAndDeleteRemovesTheMarkerOnly() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final List<Tile> tiles = nine(-1);
        final String key = FarViewCache.viewKey(DH, 1, tiles);
        cache.writeMarker(marker(key, tiles, 10, 5));
        Files.writeString(cache.library(key).resolve("DistantHorizons.sqlite"), "db");
        cache.touch(key, 999);
        assertEquals(999, cache.marker(key).orElseThrow().lastUsed());
        assertEquals(5, cache.marker(key).orElseThrow().bakedAt());
        cache.deleteMarker(key);
        assertEquals(Optional.empty(), cache.marker(key));
        assertTrue(Files.exists(cache.library(key).resolve("DistantHorizons.sqlite")), "the library stays for the next bake");
    }

    @Test
    void theMissingObjectsAreThoseNotInPacks() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final List<Tile> tiles = nine(-1);
        Files.createDirectories(cache.pack("x").getParent());
        Files.write(cache.pack(FarViewConnection.hex(tiles.get(0))), new byte[]{1});
        Files.write(cache.part(FarViewConnection.hex(tiles.get(1))), new byte[]{1});
        assertEquals(tiles.subList(1, 9), cache.missing(tiles), "a .part is not an object yet");
    }

    @Test
    void aNewKeyStartsAbsentWhileTheOldLibraryStays() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final String before = FarViewCache.viewKey(DH, 1, nine(-1));
        final String after = FarViewCache.viewKey(DH, 1, nine(2));
        cache.writeMarker(marker(before, nine(-1), 2304, 1));
        Files.writeString(cache.library(before).resolve("DistantHorizons.sqlite"), "old library");
        assertEquals(Optional.empty(), cache.marker(after), "the library of the second announcement starts absent");
        final FarViewCache.Cleaned cleaned = cache.clean(FarViewCache.LIMIT_BYTES, after);
        assertEquals(List.of(), cleaned.libraries(), "under the limit nothing is removed");
        assertTrue(Files.exists(cache.library(before).resolve("DistantHorizons.sqlite")));
    }

    /**
     * Three ready libraries with different {@code last_used}, a fourth in use that is the oldest of all,
     * and a library without a marker. Each library and each object is 100 KiB: 1,000 KiB in all over a
     * limit of 750 KiB. The library without a marker goes first, then the oldest, with the object only
     * it named; 700 KiB is under the limit, so the next library stays.
     */
    @Test
    void overTheLimitTheLeastRecentlyUsedLibrariesGoWithTheObjectsOnlyTheyNamed() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final Tile shared = tile(1, 0, 1, 50);
        final Tile onlyA = tile(0, 0, 0, 51);
        final Tile onlyB = tile(0, 0, 0, 52);
        final Tile onlyC = tile(0, 0, 0, 53);
        final Tile onlyD = tile(0, 0, 0, 54);
        final List<Tile> a = List.of(onlyA, shared);
        final List<Tile> b = List.of(onlyB, shared);
        final List<Tile> c = List.of(onlyC);
        final List<Tile> d = List.of(onlyD);
        final byte[] block = new byte[100 * 1024];
        for (Tile object : List.of(shared, onlyA, onlyB, onlyC, onlyD)) {
            Files.createDirectories(cache.pack("x").getParent());
            Files.write(cache.pack(FarViewConnection.hex(object)), block);
        }
        final String keyA = FarViewCache.viewKey(DH, 1, a);
        final String keyB = FarViewCache.viewKey(DH, 1, b);
        final String keyC = FarViewCache.viewKey(DH, 1, c);
        final String keyD = FarViewCache.viewKey(DH, 1, d);
        cache.writeMarker(marker(keyA, a, 1, 100));
        cache.writeMarker(marker(keyB, b, 1, 200));
        cache.writeMarker(marker(keyC, c, 1, 300));
        cache.writeMarker(marker(keyD, d, 1, 50));
        for (String key : List.of(keyA, keyB, keyC, keyD, "ffffffffffffffff")) {
            Files.createDirectories(cache.library(key));
            Files.write(cache.library(key).resolve("DistantHorizons.sqlite"), block);
        }
        final long markers = 4 * Files.size(cache.library(keyA).resolve(FarViewCache.MARKER));

        final FarViewCache.Cleaned cleaned = cache.clean(750 * 1024 + markers, keyD);

        assertEquals(List.of("ffffffffffffffff", keyA), cleaned.libraries());
        assertEquals(List.of(FarViewConnection.hex(onlyA)), cleaned.packs());
        assertFalse(Files.exists(cache.library(keyA)));
        assertFalse(Files.exists(cache.library("ffffffffffffffff")));
        assertFalse(Files.exists(cache.pack(FarViewConnection.hex(onlyA))));
        for (String kept : List.of(keyB, keyC, keyD)) assertTrue(Files.exists(cache.library(kept)), kept);
        for (Tile kept : List.of(shared, onlyB, onlyC, onlyD)) {
            assertTrue(Files.exists(cache.pack(FarViewConnection.hex(kept))), kept.toString());
        }
        assertEquals(cache.size(), cleaned.size());
        assertTrue(cleaned.size() <= 750 * 1024 + markers);
    }

    @Test
    void theLibraryInUseIsNeverRemovedEvenWhenItAloneIsOverTheLimit() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final List<Tile> tiles = List.of(tile(0, 0, 0, 60));
        final String key = FarViewCache.viewKey(DH, 1, tiles);
        cache.writeMarker(marker(key, tiles, 1, 1));
        Files.write(cache.library(key).resolve("DistantHorizons.sqlite"), new byte[4096]);
        Files.createDirectories(cache.pack("x").getParent());
        Files.write(cache.pack(FarViewConnection.hex(tiles.getFirst())), new byte[4096]);
        final FarViewCache.Cleaned cleaned = cache.clean(10, key);
        assertEquals(List.of(), cleaned.libraries());
        assertEquals(List.of(), cleaned.packs(), "an object the library in use names stays");
    }

    // ---- M3-S (REQ-M3-032): the .part files

    private static void part(FarViewCache cache, String sha256, long modifiedAt) throws IOException {
        Files.createDirectories(cache.part(sha256).getParent());
        Files.write(cache.part(sha256), new byte[100]);
        Files.setLastModifiedTime(cache.part(sha256), FileTime.fromMillis(modifiedAt));
    }

    @Test
    void atStartOnlyPartsOlderThanADayGo() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final long now = System.currentTimeMillis();
        part(cache, "a".repeat(64), now - Duration.ofHours(25).toMillis());
        part(cache, "b".repeat(64), now - Duration.ofHours(1).toMillis());
        part(cache, "c".repeat(64), now - Duration.ofHours(23).toMillis());

        assertEquals(List.of("a".repeat(64)), cache.dropStaleParts(null, now));
        assertFalse(Files.exists(cache.part("a".repeat(64))));
        assertTrue(Files.exists(cache.part("b".repeat(64))));
        assertTrue(Files.exists(cache.part("c".repeat(64))));
    }

    @Test
    void aPartThatCannotBeDeletedIsLoggedAndSkipped() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")), "root deletes in a read only directory");
        final FarViewCache cache = new FarViewCache(dir);
        final long now = System.currentTimeMillis();
        part(cache, "a".repeat(64), now - Duration.ofHours(25).toMillis());
        part(cache, "b".repeat(64), now - Duration.ofHours(1).toMillis());
        final Path packs = cache.part("a".repeat(64)).getParent();
        Files.setPosixFilePermissions(packs, PosixFilePermissions.fromString("r-xr-xr-x"));
        try (LogLines log = new LogLines()) {
            assertEquals(List.of(), cache.dropStaleParts(null, now));
            assertTrue(Files.exists(cache.part("a".repeat(64))), "still there");
            assertTrue(Files.exists(cache.part("b".repeat(64))));
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            assertTrue(log.warnings().getFirst().contains("Far view cache " + "a".repeat(64) + ".hfp.part cannot be deleted: "),
                    log.warnings().toString());
        } finally {
            Files.setPosixFilePermissions(packs, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void cleaningTakesEveryPart() throws IOException {
        final FarViewCache cache = new FarViewCache(dir);
        final long now = System.currentTimeMillis();
        part(cache, "a".repeat(64), now);
        part(cache, "b".repeat(64), now);
        Files.write(cache.pack("c".repeat(64)), new byte[100]);

        final FarViewCache.Cleaned cleaned = cache.clean(FarViewCache.LIMIT_BYTES, "none");

        assertEquals(List.of("a".repeat(64), "b".repeat(64)), cleaned.parts());
        assertFalse(Files.exists(cache.part("a".repeat(64))));
        assertFalse(Files.exists(cache.part("b".repeat(64))));
        assertTrue(Files.exists(cache.pack("c".repeat(64))), "under the limit, an object stays");
    }
}
