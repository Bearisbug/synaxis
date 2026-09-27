package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.OverlayStats;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxHandlers;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxStreamStats;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step (3) of a map shard ({@code docs/DESIGN-M3.md} {@code API-M3-006}, {@code API-M3-002}): the view
 * and simulation distances, the start-up check of the release and the map dimension type, all before
 * anything is written; and the pieces of step (5) that do not need the chunk loader or the overlay
 * store.
 */
public class ShardWorldTest {

    private static final SynxOptions SYNX = new SynxOptions(true, 1 << 20);
    private static final OverlayOptions OVERLAY = new OverlayOptions(16, 1);

    @TempDir
    static Path baked;
    private static ShardSettings release;

    @TempDir
    Path copies;

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(baked);
    }

    private static IllegalArgumentException refused(int viewDistance, ShardSettings settings) {
        final Env env = Env.create(config -> config.chunkViewDistance(viewDistance));
        try {
            return assertThrows(IllegalArgumentException.class,
                    () -> ShardWorld.prepare(env.process(), settings, SYNX, OVERLAY, null));
        } finally {
            env.process().stop();
        }
    }

    @Test
    public void aReleaseThatPassesRegistersTheMapDimensionTypeFromItsWorld() {
        final Env env = Env.create(config -> config.chunkViewDistance(12));
        try {
            final ShardWorld world = ShardWorld.prepare(env.process(), release, SYNX, OVERLAY, null);
            assertNotNull(world);
            final DimensionType map = env.process().dimensionType().get(RegistryKey.unsafeOf(Key.key(ShardWorld.DIMENSION_TYPE)));
            assertNotNull(map, "synaxis:map is registered");
            assertEquals(0, map.minY());
            assertEquals(64, map.height());
        } finally {
            env.process().stop();
        }
    }

    /**
     * §19 「分片与烘焙器登记同一份处理器清单」 (REQ-M3-022): the shard registers the handlers
     * {@link SynxHandlers#registerAll} finds on its class path, the data markers of {@code :game-rts}, which
     * are the handlers the baker bakes with ({@code BakerMainTest#theCommandLineBakesASaveWithDataMarkers}).
     */
    @Test
    public void theShardRegistersTheHandlersOfTheBaker() {
        final Env env = Env.create(config -> config.chunkViewDistance(12));
        try {
            ShardWorld.prepare(env.process(), release, SYNX, OVERLAY, null);
            final Set<Key> listed = SynxHandlers.registerAll(new BlockManager());
            assertEquals(Set.of(Key.key("synaxis:door"), Key.key("synaxis:loot")), listed);
            for (Key key : listed) {
                final BlockHandler handler = env.process().block().getHandler(key.asString());
                assertNotNull(handler, key.asString() + " is registered");
                assertEquals(key, handler.getKey());
            }
            assertEquals("io.github.bearisbug.synaxis.game.rts.marker.DoorMarker",
                    env.process().block().getHandler("synaxis:door").getClass().getName());
            final BlockHandler loot = env.process().block().getHandler("synaxis:loot");
            assertEquals("io.github.bearisbug.synaxis.game.rts.marker.LootMarker", loot.getClass().getName());
            assertEquals(List.of("table"), loot.getBlockEntityTags().stream().map(Tag::key).toList());
        } finally {
            env.process().stop();
        }
    }

    /** §19 「视距越界配置被拒」: a view distance above 16. */
    @Test
    public void aViewDistanceAboveSixteenIsRefused() {
        final IllegalArgumentException refused = refused(17, release);
        assertTrue(refused.getMessage().startsWith("synaxis.chunk-view-distance=17 is above 16"), refused.getMessage());
    }

    /** §19 「视距越界配置被拒」: a simulation distance above the view distance. */
    @Test
    public void aSimulationDistanceAboveTheViewDistanceIsRefused() {
        final ShardSettings far = new ShardSettings(release.mapRoot(), release.mapBuild(), 13, 100);
        final IllegalArgumentException refused = refused(12, far);
        assertTrue(refused.getMessage().contains("synaxis.simulation-distance=13"), refused.getMessage());
        assertTrue(refused.getMessage().contains("cannot exceed the view distance"), refused.getMessage());
        final ShardSettings none = new ShardSettings(release.mapRoot(), release.mapBuild(), 0, 100);
        assertTrue(refused(12, none).getMessage().contains("synaxis.simulation-distance=0"));
    }

    /** §19 「清单不符时拒绝起服」, the process half: one byte of a region file changed. */
    @Test
    public void aChangedByteRefusesWithThePayloadHashOfTheFile() throws IOException {
        final ShardSettings copy = MapFixture.copy(release, copies);
        final Path region = copy.buildDir().resolve("regions").resolve(SynxFormat.regionFileName(0, 0));
        final byte[] bytes = Files.readAllBytes(region);
        bytes[bytes.length - 100] ^= 0x01;
        Files.write(region, bytes);

        final IllegalArgumentException refused = refused(12, copy);
        assertTrue(refused.getMessage().startsWith("synx-payload-hash: r.0.0.synx: "), refused.getMessage());
    }

    /** §19 「光照引擎版本变化强制重烘」, the process half. */
    @Test
    public void aReleaseOfAnotherLightEngineVersionIsRefused() throws IOException {
        final ShardSettings copy = MapFixture.copy(release, copies);
        final Path manifest = copy.buildDir().resolve(SynxManifest.FILE_NAME);
        final String json = Files.readString(manifest);
        assertTrue(json.contains("\"light_engine_version\": 1,"), json);
        Files.writeString(manifest, json.replace("\"light_engine_version\": 1,", "\"light_engine_version\": 0,"));

        final IllegalArgumentException refused = refused(12, copy);
        assertTrue(refused.getMessage().startsWith("synx-light-engine-mismatch: manifest.json: "), refused.getMessage());
    }

    @Test
    public void aMissingManifestIsRefused() throws IOException {
        final ShardSettings copy = MapFixture.copy(release, copies);
        Files.delete(copy.buildDir().resolve(SynxManifest.FILE_NAME));
        assertTrue(refused(12, copy).getMessage().startsWith("synx-manifest: manifest.json: missing"));
    }

    /**
     * A shard directory of the fixture map built with {@code new}: r0 the west half, r1 the east half,
     * each with the gates given.
     */
    private static ShardDirectory halves(String mapId, List<ShardDirectory.Gate> gatesOfR0) {
        return new ShardDirectory(mapId, List.of(
                new ShardDirectory.Region("r0", new SynxManifest.Bounds(0, 0, 7, 15), gatesOfR0),
                new ShardDirectory.Region("r1", new SynxManifest.Bounds(8, 0, 15, 15), List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 200),
                        new ShardDirectory.Channel("r1-c1", "r1", 1, "127.0.0.1", 25602, 200)), "r0-c1");
    }

    private static IllegalArgumentException refused(ShardDirectory directory) {
        final Env env = Env.create(config -> config.chunkViewDistance(12));
        try {
            return assertThrows(IllegalArgumentException.class,
                    () -> ShardWorld.prepare(env.process(), release, SYNX, OVERLAY, directory));
        } finally {
            env.process().stop();
        }
    }

    @Test
    public void aShardDirectoryThatFitsTheReleasePasses() {
        final Env env = Env.create(config -> config.chunkViewDistance(12));
        try {
            assertNotNull(ShardWorld.prepare(env.process(), release, SYNX, OVERLAY, halves(MapFixture.MAP_ID, List.of())));
        } finally {
            env.process().stop();
        }
    }

    /** The directory does not know its file: the refusal of the check against the release names it. */
    @Test
    public void aShardDirectoryOfAnotherMapIsRefusedNamingItsFile() {
        final IllegalArgumentException refused = refused(halves("synth", List.of()));
        final Path file = Objects.requireNonNull(release.mapRoot()).resolve(ShardDirectory.FILE_NAME).toAbsolutePath();
        assertTrue(refused.getMessage().startsWith("the shard directory " + file + " is not valid: map_id synth is not "
                + MapFixture.MAP_ID + ", "), refused.getMessage());
    }

    /**
     * §19 「分片目录写错时拒启」, the last sentence: the spawn column of r0 is x 56, z 120, the center
     * of its center chunk 3, 7; a gate whose x and z take it in refuses in step (3), one next to it not.
     */
    @Test
    public void theSpawnColumnInsideAGateOfTheRegionIsRefused() {
        final ShardDirectory.Gate onIt = new ShardDirectory.Gate("r0-east", new ShardDirectory.Frame(50, 60, 110, 56, 63, 130),
                "r1", new Pos(30.5, 41, 30.5));
        assertEquals("the spawn column 56, 120 of r0 is inside the gate r0-east: a player would be sent away on joining",
                refused(halves(MapFixture.MAP_ID, List.of(onIt))).getMessage());

        final ShardDirectory.Gate nextToIt = new ShardDirectory.Gate("r0-east", new ShardDirectory.Frame(57, 0, 110, 60, 63, 130),
                "r1", new Pos(30.5, 41, 30.5));
        final Env env = Env.create(config -> config.chunkViewDistance(12));
        try {
            assertNotNull(ShardWorld.prepare(env.process(), release, SYNX, OVERLAY, halves(MapFixture.MAP_ID, List.of(nextToIt))));
        } finally {
            env.process().stop();
        }
    }

    /**
     * The column is inside a gate only if both its x and its z are: a gate over x 56 but south or north
     * of z 120, or over z 120 but west of x 56, leaves it free (east of it is the case above).
     */
    @Test
    public void aGateOverTheSpawnColumnOnOneAxisOnlyPasses() {
        final List<ShardDirectory.Frame> beside = List.of(
                new ShardDirectory.Frame(50, 0, 121, 60, 63, 130),
                new ShardDirectory.Frame(50, 0, 100, 60, 63, 119),
                new ShardDirectory.Frame(40, 0, 110, 55, 63, 130));
        for (ShardDirectory.Frame frame : beside) {
            final ShardDirectory directory = halves(MapFixture.MAP_ID,
                    List.of(new ShardDirectory.Gate("r0-east", frame, "r1", new Pos(30.5, 41, 30.5))));
            assertDoesNotThrow(() -> ShardWorld.checkSpawnColumn(directory), frame.toString());
        }
    }

    /** §19: the example of API-M3-010 with r0-east over x 1,012 to 1,020, z 1,012 to 1,020, on the 4k map. */
    @Test
    public void theGateOverTheSpawnColumnOfTheDesignIsFound() {
        final ShardDirectory directory = ShardDirectory.load(ShardDirectoryTest.resource("spawn-in-gate.json"), "r0-c1");
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ShardWorld.checkSpawnColumn(directory));
        assertEquals("the spawn column 1016, 1016 of r0 is inside the gate r0-east: a player would be sent away on joining",
                refused.getMessage());
        ShardWorld.checkSpawnColumn(ShardDirectory.load(ShardDirectoryTest.resource(ShardDirectory.FILE_NAME), "r0-c1"));
    }

    @Test
    public void theViewBoundsReachSeventeenChunksPastTheBounds() {
        assertEquals(new SynxManifest.Bounds(-17, -17, 32, 32), ShardWorld.viewBounds(MapFixture.BOUNDS));
    }

    /** Centered on the bounds and as wide as their long side, 16 chunks here. */
    @Test
    public void theWorldBorderCoversTheBounds() {
        final WorldBorder border = ShardWorld.worldBorder(MapFixture.BOUNDS);
        assertEquals(128, border.centerX());
        assertEquals(128, border.centerZ());
        assertEquals(256, border.diameter());
        final WorldBorder wide = ShardWorld.worldBorder(new SynxManifest.Bounds(-4, 0, 3, 1));
        assertEquals(0, wide.centerX());
        assertEquals(16, wide.centerZ());
        assertEquals(128, wide.diameter());
    }

    /** §28.1: the center column of the center chunk of the bounds, standing on its surface_y of segment ②. */
    @Test
    public void thePlayersSpawnOnTheSurfaceOfTheCenterColumn() throws Exception {
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
        assertEquals(new Pos(7 * 16 + 8.5, MapFixture.FLOOR, 7 * 16 + 8.5), ShardWorld.spawn(release.buildDir(), manifest, manifest.bounds()));
    }

    /** In the directory mode the spawn point is the center column of the region, not of the release. */
    @Test
    public void theSpawnPointIsTheCenterColumnOfTheRegion() throws Exception {
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
        assertEquals(new Pos(3 * 16 + 8.5, MapFixture.FLOOR, 7 * 16 + 8.5),
                ShardWorld.spawn(release.buildDir(), manifest, new SynxManifest.Bounds(0, 0, 7, 15)));
    }

    /** §20: the counters are the difference of two readings, the rest as read. */
    @Test
    public void theStreamStatisticsAreTheDifferenceOfTwoReadings() {
        final Map<String, Object> event = ShardWorld.streamStats(
                new SynxStreamStats(10, 1, 1_000, 0, 50, 5, 100, 2, 4_000),
                new SynxStreamStats(25, 3, 2_500, 1, 70, 9, 300, 2, 4_000),
                new OverlayStats(3, 1, 0, 10, 4, 1.5, 2.0, 7),
                new OverlayStats(5, 2, 1, 16, 6, 3.5, 4.0, 11));
        assertEquals(List.of("handle_sent", "overlay_sent", "bytes_sent", "failed", "loaded", "overlay_resident",
                "overlay_evicted", "evict_failed", "flush_appended", "flush_fsyncs", "flush_lag_s_max",
                "write_phase_ms_max", "relight_queue_p99_ms", "relight_ms_p99", "unloaded", "section_cache_bytes",
                "flag_conflicts"), List.copyOf(event.keySet()));
        assertEquals(15L, event.get("handle_sent"));
        assertEquals(2L, event.get("overlay_sent"));
        assertEquals(1_500L, event.get("bytes_sent"));
        assertEquals(1L, event.get("failed"));
        assertEquals(20L, event.get("loaded"));
        assertEquals(5, event.get("overlay_resident"));
        assertEquals(1L, event.get("overlay_evicted"));
        assertEquals(1L, event.get("evict_failed"));
        assertEquals(6L, event.get("flush_appended"));
        assertEquals(2L, event.get("flush_fsyncs"));
        assertEquals(3.5, event.get("flush_lag_s_max"));
        assertEquals(4.0, event.get("write_phase_ms_max"));
        assertEquals(4L, event.get("unloaded"));
        assertEquals(300L, event.get("section_cache_bytes"));
        assertEquals(0, event.get("flag_conflicts"));
    }

    /**
     * The write phase of the overlay store is a repeating task at the end of the tick, which the
     * scheduler stops for good once it throws: a throw is reported and the next tick runs it again,
     * or no dirty chunk would be written, unloaded or flushed at the stop any more.
     */
    @Test
    public void aWritePhaseThatThrowsRunsAgainOnTheNextTick() {
        final Env env = Env.create(config -> config);
        try {
            final List<Throwable> reported = new CopyOnWriteArrayList<>();
            env.process().exception().setExceptionHandler(reported::add);
            final AtomicInteger runs = new AtomicInteger();
            ShardWorld.scheduleWritePhase(env.process(), () -> {
                if (runs.incrementAndGet() == 1) throw new IllegalStateException("write phase");
            });

            for (int i = 0; i < 3; i++) env.tick();

            assertEquals(3, runs.get(), "the ticks after the throw run the write phase too");
            assertEquals(1, reported.size(), reported.toString());
            assertEquals("write phase", reported.getFirst().getMessage());
        } finally {
            env.process().stop();
        }
    }
}
