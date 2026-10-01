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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far view announcement a map shard computes when it starts ({@code docs/DESIGN-M3.md}
 * {@code API-M3-015} 远景宣告, §19 the scenarios of {@code REQ-M3-024} on the shard). The packs here
 * are written straight through {@link FarViewPack.Writer} into a baked release and its manifest; the
 * baker writes the real ones from track S on.
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

    /** Bakes the four tile map and writes a pack for each of its tiles into the release and its manifest. */
    private static ShardSettings fourTileRelease(Path mapsRoot, String urlRoot) throws IOException, SynxException {
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, 40, Block.STONE), 0, 64, FOUR_TILES), Registries.vanilla());
        final Baker.Result result = Baker.bake(new Baker.Request("four", source, mapsRoot, 1, mapsRoot.resolve("logs"),
                Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        assertNotNull(result.directory(), result.toString());
        final Path build = result.directory();
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(build.resolve(SynxManifest.FILE_NAME)));
        final List<SynxManifest.FarView> farview = new ArrayList<>();
        Files.createDirectories(build.resolve("farview"));
        for (int tileX = 0; tileX <= 1; tileX++) {
            for (int tileZ = -1; tileZ <= 0; tileZ++) {
                final FarViewPack.Writer writer = new FarViewPack.Writer(2, tileX, tileZ, 0, 64, Block.statesCount(), 776,
                        List.of("minecraft:plains"))
                        .block(FarViewPack.Layer.COARSE, tileX == 0 ? 3 : 0, tileZ == 0 ? 0 : 3, stone(30 + tileX * 2 + tileZ))
                        .block(FarViewPack.Layer.FINE, tileX == 0 ? 15 : 0, tileZ == 0 ? 0 : 15, stone(39));
                final byte[] pack = writer.write();
                final byte[] prefix = writer.coarsePrefix();
                Files.write(build.resolve("farview").resolve("t." + tileX + "." + tileZ + ".hfp"), pack);
                farview.add(new SynxManifest.FarView(tileX, tileZ, 2, 4, pack.length, SynxDigests.sha256(pack),
                        prefix.length, SynxDigests.sha256(prefix)));
            }
        }
        final SynxManifest withPacks = new SynxManifest(manifest.mapId(), manifest.buildId(), manifest.contentHash(),
                manifest.mcVersion(), manifest.protocolVersion(), manifest.registryDigest(), manifest.lightEngineVersion(),
                manifest.bakerVersion(), manifest.world(), manifest.bounds(), manifest.regions(), farview,
                manifest.source(), manifest.bake(), manifest.verify());
        Files.writeString(build.resolve(SynxManifest.FILE_NAME), withPacks.toJson());
        return new ShardSettings(mapsRoot.resolve("four"), build.getFileName().toString(), 4, 100, urlRoot);
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

    /** §19 「分片不宣告远景的情形」: no url root, no tile in the bounds, a pack that does not match. */
    @Test
    public void aShardWithoutAUrlRootOrAPackToAnnounceAnnouncesNothing(@TempDir Path maps)
            throws IOException, SynxException {
        final Started noRoot = start(fourTileRelease(maps.resolve("a"), ""), directory());
        assertNull(noRoot.farView());
        assertEquals(List.of("Far view off: synaxis.farview.url-root is empty"), farViewLines(noRoot, Level.INFO));
        assertEquals(0, noRoot.attributes().get("farview_tiles"));

        final ShardSettings fixture = MapFixture.bake(maps.resolve("b"));
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
}
