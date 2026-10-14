package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement.Tile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state of one connection's library ({@code docs/DESIGN-M3.md} §11.3 {@code ENT-FarViewLibrary})
 * around a cancel: what the bake thread may still move after the connection or the level ended, and
 * the load check of a ready library ({@code API-M3-015} 写库条第 6、8 步).
 */
class FarViewConnectionTest {

    private static final long SOURCES = 2304;

    @TempDir
    Path dir;

    private FarViewCache cache;
    private final List<Tile> tiles = List.of(tile(-1, 0, FarViewAnnouncement.LAYER_COARSE, 1),
            tile(0, 0, FarViewAnnouncement.LAYER_FULL, 2));
    private final List<Boolean> started = new ArrayList<>();

    private static Tile tile(int x, int z, int layer, int seed) {
        final byte[] sha = new byte[32];
        Arrays.fill(sha, (byte) seed);
        return new Tile(x, z, layer, 1000, sha, "http://cdn.test/m/farview/" + seed + ".hfp");
    }

    @BeforeEach
    void packs() throws IOException {
        cache = new FarViewCache(dir);
        Files.createDirectories(cache.pack("x").getParent());
        for (Tile tile : tiles) Files.write(cache.pack(FarViewCache.hex(tile)), new byte[]{1});
    }

    private FarViewConnection connection() {
        return new FarViewConnection(new FarViewAnnouncement("b1", 2, 4, -64, 256, tiles), System.nanoTime(),
                new FarViewSettings(FarViewSettings.DEFAULT_RADIUS), true, FarViewClient.DH_VERSION, cache,
                FarViewDownloader.standard(cache), null);
    }

    private String key() {
        return FarViewCache.viewKey(FarViewClient.DH_VERSION, FarViewClient.BAKE_CODE_VERSION, tiles);
    }

    private void marker(long lastUsed) throws IOException {
        cache.writeMarker(new FarViewCache.Marker(FarViewCache.MARKER_FORMAT, key(), FarViewClient.DH_VERSION,
                FarViewClient.BAKE_CODE_VERSION, tiles.stream().map(FarViewCache.MarkerTile::of).toList(), SOURCES, 1, 1, lastUsed));
    }

    /** A ready library whose level loaded: the load check is under way on the bake thread. */
    private FarViewConnection loadChecking() throws IOException {
        marker(1);
        final FarViewConnection c = connection();
        c.begin();
        assertEquals(FarViewConnection.State.READY, c.state());
        c.levelLoaded("level", (_, _, loadCheck) -> started.add(loadCheck));
        assertEquals(List.of(true), started, "a matching marker starts the load check");
        return c;
    }

    @Test
    void aLibraryHoldingTheMarkersSourcesStaysReady() throws IOException {
        final FarViewConnection c = loadChecking();
        assertFalse(c.loadChecked(SOURCES));
        assertEquals(FarViewConnection.State.READY, c.state());
        assertTrue(cache.marker(key()).orElseThrow().lastUsed() > 1, "last_used is updated");
    }

    @Test
    void noAnswerOnALiveConnectionRemovesTheMarkerAndBakesAgain() throws IOException {
        final FarViewConnection c = loadChecking();
        assertTrue(c.loadChecked(null));
        assertTrue(cache.marker(key()).isEmpty());
    }

    @Test
    void noAnswerAfterACancelLeavesTheMarkerOfAGoodLibrary() throws IOException {
        final FarViewConnection c = loadChecking();
        // DH closes the library as the level or the connection ends, and the count gets no answer
        c.cancel("Distant Horizons unloaded the level");
        assertFalse(c.loadChecked(null), "nothing is baked on a connection that ended");
        assertTrue(cache.marker(key()).isPresent(), "the next connection takes the library as it is");
        assertTrue(c.state() != FarViewConnection.State.BAKING, c.state().toString());
    }

    @Test
    void aBakeThreadThatRunsAfterTheCancelStaysOutOfBaking() {
        final FarViewConnection c = connection();
        c.begin();
        c.levelLoaded("level", (_, _, loadCheck) -> started.add(loadCheck));
        assertEquals(List.of(false), started);
        assertEquals(FarViewConnection.State.BAKING, c.state());
        c.cancel("disconnected");
        assertEquals(FarViewConnection.State.ABSENT, c.state());
        // the bake thread, started before the cancel, gets to its first statement after it
        assertFalse(c.baking());
        assertEquals(FarViewConnection.State.ABSENT, c.state(), "the HUD line has nothing left to show");
    }

    @Test
    void aCancelBeforeTheLevelLoadsStartsNothing() {
        final FarViewConnection c = connection();
        c.begin();
        c.cancel("disconnected");
        c.levelLoaded("level", (_, _, loadCheck) -> started.add(loadCheck));
        assertEquals(List.of(), started);
        assertFalse(c.baking());
        assertEquals(FarViewConnection.State.ABSENT, c.state());
    }
}
