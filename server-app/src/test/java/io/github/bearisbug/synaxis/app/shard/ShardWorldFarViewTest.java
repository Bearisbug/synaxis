package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far view announcement a map shard computes when it starts ({@code docs/DESIGN-M3.md}
 * {@code API-M3-015} 远景宣告, §19 the scenarios of {@code REQ-M3-024} on the shard). The packs are the
 * baker's, but for the 81 tiles of {@link #moreThan64TilesTurnTheFarViewOff}, written straight through
 * {@link FarViewPack.Writer}.
 */
@Timeout(120)
public class ShardWorldFarViewTest {

    private static final SynxOptions SYNX = new SynxOptions(true, 1 << 24, 1, false);
    private static final String URL_ROOT = "http://cdn.test/synaxis";

    @TempDir
    Path overlayRoot;

    private static SynxManifest.FarView entry(int tileX, int tileZ) {
        return new SynxManifest.FarView(tileX, tileZ, 2, 4, 1000, new byte[32], 100, new byte[32]);
    }

    private static List<String> selected(List<SynxManifest.FarView> farview, SynxManifest.Bounds bounds) {
        return ShardWorld.announcedTiles(farview, bounds).stream()
                .map(tile -> tile.pack().tileX() + "," + tile.pack().tileZ() + ":" + tile.layer()).toList();
    }

    /** §19 「分片把四周 4,096 格以内的瓦片按粗层宣告」, the unit part. */
    @Test
    public void theTilesMeetingTheBoundsAreWholeAndThoseWithin4096BlocksByTheirCoarseLayer() {
        final List<SynxManifest.FarView> farview = new ArrayList<>();
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 2; z++) farview.add(entry(x, z));
        }
        final List<String> aligned = List.of("-1,-1:1", "-1,0:1", "-1,1:1", "0,-1:1", "0,0:0", "0,1:1", "1,-1:1", "1,0:1",
                "1,1:1");
        assertEquals(aligned, selected(farview, new SynxManifest.Bounds(0, 0, 255, 255)));
        assertEquals(aligned, selected(farview, new SynxManifest.Bounds(250, 0, 255, 7)));

        final List<String> across = selected(farview, new SynxManifest.Bounds(128, 128, 383, 383));
        assertEquals(List.of("0,0:0", "0,1:0", "1,0:0", "1,1:0"), across.stream().filter(t -> t.endsWith(":0")).toList());
        assertEquals(12, across.stream().filter(t -> t.endsWith(":1")).count(), across.toString());
        assertEquals(16, across.size());

        assertEquals(List.of("0,0:0"), selected(List.of(entry(0, 0), entry(2, 0)), new SynxManifest.Bounds(0, 0, 255, 255)),
                "a tile 256 chunks past the bounds is not announced");
        assertEquals(List.of("-1,0:1", "0,0:0"),
                selected(List.of(entry(0, 0), entry(-1, 0)), new SynxManifest.Bounds(0, 0, 0, 0)),
                "the coarse ring reaches 256 chunks past the bounds, here chunk -256 of tile -1");
        assertEquals(List.of("0,0:0"), selected(List.of(entry(0, 0), entry(-2, 0)), new SynxManifest.Bounds(0, 0, 0, 0)));
        assertEquals(List.of(), selected(List.of(), new SynxManifest.Bounds(0, 0, 255, 255)));
    }

    // A release with packs: the four tiles of the bounds 250,-8,262,7

    private static final SynxManifest.Bounds FOUR_TILES = new SynxManifest.Bounds(250, -8, 262, 7);
    private static final SynxManifest.Bounds R0 = new SynxManifest.Bounds(250, 0, 255, 7);

    /** Bakes the four tile map, a pack for each of its tiles in the release and its manifest. */
    public static ShardSettings fourTileRelease(Path mapsRoot, String urlRoot) {
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, 40, Block.STONE), 0, 64, FOUR_TILES), Registries.vanilla());
        final Baker.Result result = Baker.bake(new Baker.Request("four", source, mapsRoot, 1, mapsRoot.resolve("logs"),
                Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.toString());
        assertNotNull(result.directory());
        return new ShardSettings(mapsRoot.resolve("four"), result.directory().getFileName().toString(), 4, 100, urlRoot);
    }

    private static List<FarViewPack.Sample> stone(int y) {
        return Collections.nCopies(FarViewPack.BLOCK_SAMPLES, new FarViewPack.Sample(Block.STONE.stateId(), 0, y, 0, 0, 0));
    }

    private static ShardDirectory directory() {
        return new ShardDirectory("four", List.of(new ShardDirectory.Region("r0", R0, List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 200)), "r0-c1");
    }

    /** The player store of a shard nobody joined: saved at once. */
    private static final class NoPlayers implements ShutdownParticipant {
        @Override
        public void onGateClosed(Duration countdown) {
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
        }
    }

    private record Started(byte @Nullable [] farView, Map<String, Object> attributes, List<ILoggingEvent> log) {
    }

    /** Steps (3) and (5) of {@code Main}, the lines of {@code ShardWorld} kept. */
    private Started start(ShardSettings settings, @Nullable ShardDirectory directory) {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                .chunkViewDistance(12).build());
        final Logger logger = (Logger) LoggerFactory.getLogger(ShardWorld.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            final ShardWorld world = ShardWorld.prepare(process, settings, SYNX, new OverlayOptions(16_384, 10), directory);
            final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
            process.setShutdownParticipant(shutdown);
            final ShardWorld.Started started = world.start(shutdown, (_, _) -> {
            }, null, overlayRoot);
            return new Started(started.farView(), world.startedAttributes(), List.copyOf(appender.list));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            process.stop();
        }
    }

    private static List<String> farViewLines(Started started, Level level) {
        return started.log().stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).filter(line -> line.startsWith("Far view")).toList();
    }

    /**
     * §19 「分片把四周 4,096 格以内的瓦片按粗层宣告」, the shard part: the region r0 of the four tile
     * release announces its own tile whole and the three around it by their coarse prefix.
     */
    @Test
    public void aShardAnnouncesItsTileWholeAndTheTilesAroundByTheirCoarsePrefix(@TempDir Path maps)
            throws IOException, SynxException {
        final ShardSettings settings = fourTileRelease(maps, URL_ROOT);
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(settings.buildDir().resolve(SynxManifest.FILE_NAME)));
        final Started started = start(settings, directory());

        assertNotNull(started.farView(), started.log().toString());
        final MsgBuf buf = MsgBuf.reading(started.farView());
        final FarViewAnnouncement announcement = FarViewAnnouncement.read(buf);
        assertEquals(0, buf.remaining());
        assertEquals(manifest.buildId(), announcement.buildId());
        assertEquals(2, announcement.baseLevel());
        assertEquals(4, announcement.coarseLevel());
        assertEquals(0, announcement.minY());
        assertEquals(64, announcement.height());
        assertEquals(List.of("0,-1:1", "0,0:0", "1,-1:1", "1,0:1"), announcement.tiles().stream()
                .map(tile -> tile.tileX() + "," + tile.tileZ() + ":" + tile.layer()).toList());
        for (FarViewAnnouncement.Tile tile : announcement.tiles()) {
            final SynxManifest.FarView pack = manifest.farview().stream()
                    .filter(p -> p.tileX() == tile.tileX() && p.tileZ() == tile.tileZ()).findFirst().orElseThrow();
            final boolean whole = tile.layer() == FarViewAnnouncement.LAYER_FULL;
            final byte[] digest = whole ? pack.sha256() : pack.coarseSha256();
            assertEquals(whole ? pack.bytes() : pack.coarseBytes(), tile.bytes());
            assertArrayEquals(digest, tile.sha256());
            assertEquals(URL_ROOT + "/four/farview/" + SynxDigests.hex(digest) + ".hfp", tile.url());
        }
        assertEquals(List.of("Far view: 1 tile(s), 3 coarse, from " + URL_ROOT), farViewLines(started, Level.INFO));
        assertEquals(4, started.attributes().get("farview_tiles"));
    }

    /**
     * §19 「分片在 configuration 阶段宣告本大区的瓦片」, what the shard announces (the wire is
     * {@code FarViewLaunchTest}'s): the map of {@link MapFixture} lies in tile 0, 0 alone, so the announcement
     * is that tile whole, with no coarse tile, its size and digest those of the manifest.
     */
    @Test
    public void aShardOnAMapOfOneTileAnnouncesThatTileWhole(@TempDir Path maps) throws IOException, SynxException {
        final ShardSettings fixture = MapFixture.bake(maps);
        final ShardSettings settings = new ShardSettings(fixture.mapRoot(), fixture.mapBuild(), 4, 100, URL_ROOT);
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(settings.buildDir().resolve(SynxManifest.FILE_NAME)));
        final Started started = start(settings, null);

        assertNotNull(started.farView(), started.log().toString());
        final MsgBuf buf = MsgBuf.reading(started.farView());
        final FarViewAnnouncement announcement = FarViewAnnouncement.read(buf);
        assertEquals(0, buf.remaining());
        assertEquals(manifest.buildId(), announcement.buildId());
        assertEquals(4, announcement.coarseLevel());
        assertEquals(1, announcement.tiles().size());
        final FarViewAnnouncement.Tile tile = announcement.tiles().getFirst();
        final SynxManifest.FarView pack = manifest.farview().getFirst();
        assertEquals(List.of(0, 0, FarViewAnnouncement.LAYER_FULL), List.of(tile.tileX(), tile.tileZ(), tile.layer()));
        assertEquals(pack.bytes(), tile.bytes());
        assertArrayEquals(pack.sha256(), tile.sha256());
        assertEquals(URL_ROOT + "/" + MapFixture.MAP_ID + "/farview/" + SynxDigests.hex(pack.sha256()) + ".hfp", tile.url());
        assertEquals(List.of("Far view: 1 tile(s), 0 coarse, from " + URL_ROOT), farViewLines(started, Level.INFO));
        assertEquals(1, started.attributes().get("farview_tiles"));
    }

    /**
     * §19 「分片不宣告远景的情形」: no url root; a release baked before M3-G, whose {@code farview[]} is empty
     * (a pack that does not match is {@link #aPackThatDoesNotMatchItsManifestStopsTheWholeAnnouncement}).
     */
    @Test
    public void aShardWithoutAUrlRootOrAPackToAnnounceAnnouncesNothing(@TempDir Path maps)
            throws IOException, SynxException {
        final Started noRoot = start(fourTileRelease(maps.resolve("a"), ""), directory());
        assertNull(noRoot.farView());
        assertEquals(List.of("Far view off: synaxis.farview.url-root is empty"), farViewLines(noRoot, Level.INFO));
        assertEquals(0, noRoot.attributes().get("farview_tiles"));

        final ShardSettings fixture = MapFixture.bake(maps.resolve("b"));
        final Path manifestFile = fixture.buildDir().resolve(SynxManifest.FILE_NAME);
        Files.writeString(manifestFile, withFarView(SynxManifest.fromJson(Files.readString(manifestFile)), List.of()).toJson());
        final Started noTile = start(new ShardSettings(fixture.mapRoot(), fixture.mapBuild(), 4, 100, URL_ROOT), null);
        assertNull(noTile.farView());
        assertEquals(1, farViewLines(noTile, Level.INFO).size(), noTile.log().toString());
        assertTrue(farViewLines(noTile, Level.INFO).getFirst().startsWith("Far view off: no far view tile of "),
                farViewLines(noTile, Level.INFO).toString());
        assertEquals(0, noTile.attributes().get("farview_tiles"));
    }

    @Test
    public void aPackThatDoesNotMatchItsManifestStopsTheWholeAnnouncement(@TempDir Path maps)
            throws IOException, SynxException {
        // A byte of the coarse prefix of a tile announced by its prefix, past the header
        final ShardSettings prefix = fourTileRelease(maps.resolve("prefix"), URL_ROOT);
        flip(prefix.buildDir().resolve("farview").resolve("t.1.0.hfp"), 70);
        assertRefusedWithWarning(start(prefix, directory()), "farview/t.1.0.hfp");

        // A byte of the fine layer of the tile announced whole
        final ShardSettings whole = fourTileRelease(maps.resolve("whole"), URL_ROOT);
        final Path pack = whole.buildDir().resolve("farview").resolve("t.0.0.hfp");
        flip(pack, (int) Files.size(pack) - 1);
        assertRefusedWithWarning(start(whole, directory()), "farview/t.0.0.hfp");

        // The manifest's coarse_bytes one short of the pack's fine_offset, the whole file as listed
        final ShardSettings bytes = fourTileRelease(maps.resolve("bytes"), URL_ROOT);
        final Path manifestFile = bytes.buildDir().resolve(SynxManifest.FILE_NAME);
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(manifestFile));
        final List<SynxManifest.FarView> farview = new ArrayList<>();
        for (SynxManifest.FarView entry : manifest.farview()) {
            farview.add(entry.tileX() != 1 || entry.tileZ() != 0 ? entry : new SynxManifest.FarView(1, 0, 2, 4, entry.bytes(),
                    entry.sha256(), entry.coarseBytes() - 1, entry.coarseSha256()));
        }
        Files.writeString(manifestFile, new SynxManifest(manifest.mapId(), manifest.buildId(), manifest.contentHash(),
                manifest.mcVersion(), manifest.protocolVersion(), manifest.registryDigest(), manifest.lightEngineVersion(),
                manifest.bakerVersion(), manifest.world(), manifest.bounds(), manifest.regions(), farview,
                manifest.source(), manifest.bake(), manifest.verify()).toJson());
        assertRefusedWithWarning(start(bytes, directory()), "farview/t.1.0.hfp");

        // A pack missing from the release
        final ShardSettings missing = fourTileRelease(maps.resolve("missing"), URL_ROOT);
        Files.delete(missing.buildDir().resolve("farview").resolve("t.0.-1.hfp"));
        assertRefusedWithWarning(start(missing, directory()), "farview/t.0.-1.hfp");
    }

    /** The manifest of a release with {@code farview[]} replaced. */
    private static SynxManifest withFarView(SynxManifest manifest, List<SynxManifest.FarView> farview) {
        return new SynxManifest(manifest.mapId(), manifest.buildId(), manifest.contentHash(), manifest.mcVersion(),
                manifest.protocolVersion(), manifest.registryDigest(), manifest.lightEngineVersion(), manifest.bakerVersion(),
                manifest.world(), manifest.bounds(), manifest.regions(), farview, manifest.source(), manifest.bake(),
                manifest.verify());
    }

    /** Rewrites the manifest entry of tile 1,0 of a release. */
    private static void rewriteTileOneZero(ShardSettings release, UnaryOperator<SynxManifest.FarView> change)
            throws IOException, SynxException {
        final Path manifestFile = release.buildDir().resolve(SynxManifest.FILE_NAME);
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(manifestFile));
        Files.writeString(manifestFile, withFarView(manifest, manifest.farview().stream()
                .map(entry -> entry.tileX() == 1 && entry.tileZ() == 0 ? change.apply(entry) : entry).toList()).toJson());
    }

    /** The coarse digest of the manifest is checked on its own: the file and its whole digest still match. */
    @Test
    public void aCoarseDigestThatAloneDoesNotMatchStopsTheWholeAnnouncement(@TempDir Path maps)
            throws IOException, SynxException {
        final ShardSettings release = fourTileRelease(maps, URL_ROOT);
        rewriteTileOneZero(release, entry -> new SynxManifest.FarView(1, 0, 2, 4, entry.bytes(), entry.sha256(),
                entry.coarseBytes(), new byte[32]));
        assertRefusedWithWarning(start(release, directory()), "farview/t.1.0.hfp");
    }

    /** One announcement has one base_level and one coarse_level, so tiles that disagree announce nothing. */
    @Test
    public void tilesOfOtherLevelsStopTheWholeAnnouncement(@TempDir Path maps) throws IOException, SynxException {
        final ShardSettings release = fourTileRelease(maps, URL_ROOT);
        rewriteTileOneZero(release, entry -> new SynxManifest.FarView(1, 0, 1, 4, entry.bytes(), entry.sha256(),
                entry.coarseBytes(), entry.coarseSha256()));
        final Started started = start(release, directory());
        assertRefusedWithWarning(started, "farview/t.1.0.hfp");
        assertTrue(farViewLines(started, Level.WARN).getFirst().contains("levels 1/4"), started.log().toString());
    }

    /**
     * What makes no announcement turns the far view off and lets the shard start: a url root so long that
     * the url of a tile is over 1,024 bytes.
     */
    @Test
    public void aUrlOverTheCapTurnsTheFarViewOffAndTheShardStarts(@TempDir Path maps) throws IOException, SynxException {
        final String longRoot = "http://cdn.test/" + "a".repeat(1000);
        final Started started = start(fourTileRelease(maps, longRoot), directory());
        assertRefusedWithWarning(started, "url");
    }

    /** Over 64 tiles to announce, the bounds of a region 7 × 7 tiles wide: 49 whole and 32 coarse. */
    @Test
    public void moreThan64TilesTurnTheFarViewOff(@TempDir Path maps) throws IOException, SynxException {
        final ShardSettings release = fourTileRelease(maps.resolve("four"), URL_ROOT);
        final SynxManifest four = SynxManifest.fromJson(Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
        final Path build = Files.createDirectories(maps.resolve("wide").resolve("farview")).getParent();
        final List<SynxManifest.FarView> farview = new ArrayList<>();
        for (int tileX = -1; tileX <= 7; tileX++) {
            for (int tileZ = -1; tileZ <= 7; tileZ++) {
                final FarViewPack.Writer writer = new FarViewPack.Writer(2, tileX, tileZ, 0, 64, Block.statesCount(), 776,
                        List.of("minecraft:plains")).block(FarViewPack.Layer.COARSE, 0, 0, stone(30));
                final byte[] pack = writer.write(), prefix = writer.coarsePrefix();
                Files.write(build.resolve("farview").resolve("t." + tileX + "." + tileZ + ".hfp"), pack);
                farview.add(new SynxManifest.FarView(tileX, tileZ, 2, 4, pack.length, SynxDigests.sha256(pack), prefix.length,
                        SynxDigests.sha256(prefix)));
            }
        }
        final SynxManifest.Bounds sevenBySeven = new SynxManifest.Bounds(0, 0, 7 * 256 - 1, 7 * 256 - 1);
        assertEquals(81, ShardWorld.announcedTiles(farview, sevenBySeven).size());

        final Logger logger = (Logger) LoggerFactory.getLogger(ShardWorld.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertNull(ShardWorld.farViewAnnouncement(build, withFarView(four, farview), sevenBySeven, URL_ROOT));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        final List<String> warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Far view off: ") && warnings.getFirst().contains("count 81"),
                warnings.toString());
    }

    private static void flip(Path file, int at) throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        bytes[at] ^= 0x01;
        Files.write(file, bytes);
    }

    private static void assertRefusedWithWarning(Started started, String file) {
        assertNull(started.farView());
        final List<String> warnings = farViewLines(started, Level.WARN);
        assertEquals(1, warnings.size(), started.log().toString());
        assertTrue(warnings.getFirst().startsWith("Far view off: ") && warnings.getFirst().contains(file), warnings.toString());
        assertEquals(List.of(), farViewLines(started, Level.INFO));
        assertEquals(0, started.attributes().get("farview_tiles"));
    }

    /** §19 「url-root 是非回环的明文 http 时起服记 WARN」. */
    @Test
    public void aUrlRootOfPlainHttpToAnotherHostIsAnnouncedWithAWarning(@TempDir Path maps) {
        final ShardSettings release = fourTileRelease(maps, "");
        final List<Integer> warnings = new ArrayList<>();
        for (String root : List.of("http://127.0.0.1:25751", "http://cdn.test", "https://cdn.test")) {
            final Started started = start(new ShardSettings(release.mapRoot(), release.mapBuild(), 4, 100, root), directory());
            assertNotNull(started.farView(), root);
            final List<String> lines = farViewLines(started, Level.WARN);
            warnings.add(lines.size());
            if (!lines.isEmpty()) {
                assertEquals("Far view packs go over plain http to cdn.test: which tiles a player loads can be seen on the "
                        + "way, use https", lines.getFirst());
            }
        }
        assertEquals(List.of(0, 1, 0), warnings, "only the plain http to another host");
    }

    /**
     * §19 「读文件之前先比大小」, the far view pack: one of 41,943,041 bytes, its size and SHA-256 as the
     * manifest says, is not read before its size turns it down, and the shard starts without a far view.
     */
    @Test
    public void aPackOverFortyMebibytesIsTurnedDownOnItsSizeAlone(@TempDir Path maps) throws Exception {
        final ShardSettings release = fourTileRelease(maps, URL_ROOT);
        final Path pack = release.buildDir().resolve("farview").resolve("t.0.0.hfp");
        final long bytes = FarViewAnnouncement.MAX_OBJECT_BYTES + 1;
        ShardWorldFileTrustTest.sparse(pack, bytes);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] zeros = new byte[1 << 20];
        for (long left = bytes - 1; left > 0; left -= zeros.length) digest.update(zeros, 0, (int) Math.min(zeros.length, left));
        digest.update((byte) ' ');
        final byte[] sha256 = digest.digest();
        rewriteTile(release, 0, 0, entry -> new SynxManifest.FarView(0, 0, entry.baseLevel(), entry.coarseLevel(), bytes,
                sha256, entry.coarseBytes(), entry.coarseSha256()));

        final Started started = start(release, directory());
        assertNull(started.farView());
        final List<String> warnings = farViewLines(started, Level.WARN);
        assertEquals(1, warnings.size(), started.log().toString());
        assertTrue(warnings.getFirst().endsWith("farview/t.0.0.hfp of " + release.mapBuild() + " is 41943041 bytes, over "
                + "the 41943040 bytes a far view object may have"), warnings.getFirst());
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
        final SynxManifest.FarView entry = manifest.farview().stream().filter(tile -> tile.tileX() == 0 && tile.tileZ() == 0)
                .findFirst().orElseThrow();
        try (ShardWorldFileTrustTest.Counting channel = new ShardWorldFileTrustTest.Counting(pack)) {
            assertTrue(ShardWorld.mismatch(channel, entry, FarViewAnnouncement.LAYER_FULL).contains("41943041 bytes"));
            assertEquals(0, channel.read, "only its size was taken");
        }
    }

    /** Rewrites the manifest entry of a tile of a release. */
    private static void rewriteTile(ShardSettings release, int tileX, int tileZ, UnaryOperator<SynxManifest.FarView> change)
            throws IOException, SynxException {
        final Path manifestFile = release.buildDir().resolve(SynxManifest.FILE_NAME);
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(manifestFile));
        final List<SynxManifest.FarView> farview = new ArrayList<>();
        for (SynxManifest.FarView entry : manifest.farview()) {
            farview.add(entry.tileX() == tileX && entry.tileZ() == tileZ ? change.apply(entry) : entry);
        }
        Files.writeString(manifestFile, withFarView(manifest, farview).toJson());
    }
}
