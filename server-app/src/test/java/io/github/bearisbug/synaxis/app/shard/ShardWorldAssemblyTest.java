package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.OverlayChunk;
import io.github.bearisbug.synaxis.synx.OverlayEncoding;
import io.github.bearisbug.synaxis.synx.OverlayHit;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.OverlayStats;
import io.github.bearisbug.synaxis.synx.OverlayStore;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxRegion;
import io.github.bearisbug.synaxis.synx.SynxSettings;
import io.github.bearisbug.synaxis.synx.SynxSlot;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The map shard as {@code Main} assembles it, in this JVM, on a baked release and with the real
 * {@link ShutdownSequence}, no game port bound ({@code docs/DESIGN-M3.md} §19, the entry
 * 「server-app 测试 / 集成测试」; {@code REQ-M3-008}, {@code REQ-M3-010}, {@code REQ-M3-011}).
 */
@Timeout(60)
public class ShardWorldAssemblyTest {

    private static final SynxOptions SYNX = new SynxOptions(true, 1 << 24);
    private static final String SYNX_LOGGERS = "io.github.bearisbug.synaxis.synx";

    @TempDir
    static Path baked;
    private static ShardSettings release;
    private static SynxManifest manifest;

    @TempDir
    Path overlayRoot;

    @BeforeAll
    static void bake() throws IOException, SynxException {
        release = MapFixture.bake(baked);
        manifest = SynxManifest.fromJson(Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
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

    private static ServerProcess process() {
        return ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                .chunkViewDistance(12).build());
    }

    /** What {@code MinecraftServer#start} does, on an ephemeral loopback port: the tick threads and the ticker run. */
    private static void startTicking(ServerProcess process) {
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50), process.config().maxTickCatchUp()).start();
    }

    /**
     * Runs ticks on the test thread, on a process whose tick threads run: the first tick with a loaded
     * chunk waits for the tick thread of its partition, forever when {@code dispatcher().start()} was
     * not called.
     */
    private static void tick(ServerProcess process, int count) {
        for (int i = 0; i < count; i++) process.ticker().tick(System.nanoTime());
    }

    private record Mounted(ShardWorld world, ShardWorld.Started started, List<String> events) {
        InstanceContainer instance() {
            return started.instance();
        }
    }

    /** Steps (3) and (5) of {@code Main} on a map. */
    private Mounted mount(ServerProcess process, OverlayOptions overlay) {
        final ShardWorld world = ShardWorld.prepare(process, release, SYNX, overlay, null);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final List<String> events = new CopyOnWriteArrayList<>();
        return new Mounted(world, world.start(shutdown, (name, _) -> events.add(name), null, overlayRoot), events);
    }

    private OverlayStore reopen(OverlayOptions options) throws IOException, SynxException {
        return OverlayStore.open(overlayRoot, manifest, ShardWorld.overlaySettings(manifest, 256), options);
    }

    private static List<String> synxLog(Runnable action) {
        final Logger logger = (Logger) LoggerFactory.getLogger(SYNX_LOGGERS);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** §19 「挂地图的分片起服」, the part of it inside the process. */
    @Test
    public void aMountedShardServesTheMapInstance() throws IOException {
        final ServerProcess process = process();
        try {
            final Mounted mounted = mount(process, new OverlayOptions(16_384, 10));
            final InstanceContainer instance = mounted.instance();
            process.dispatcher().start();
            assertEquals(ShardWorld.DIMENSION_TYPE, instance.getDimensionType().key().asString());
            assertEquals(4, instance.simulationDistance());
            assertEquals(100, instance.chunkUnloadGraceTicks());
            assertEquals(ShardWorld.worldBorder(MapFixture.BOUNDS), instance.getWorldBorder());
            assertEquals(new Pos(7 * 16 + 8.5, MapFixture.FLOOR, 7 * 16 + 8.5), mounted.started().spawn());
            final Map<String, Object> started = mounted.world().startedAttributes();
            assertEquals(release.mapBuild(), started.get("build_id"));
            assertTrue(((Number) started.get("regions")).intValue() > 0, started.toString());
            assertTrue(((Number) started.get("bytes_mapped")).longValue() > 0, started.toString());
            assertEquals(0L, ((Number) started.get("overlay_records")).longValue());
            instance.loadChunk(7, 7).join();
            assertEquals(Block.STONE, instance.getBlock(7 * 16 + 8, MapFixture.FLOOR - 1, 7 * 16 + 8));
            tick(process, 5);
            final Path segments = overlayRoot.resolve(release.mapBuild());
            try (Stream<Path> files = Files.exists(segments) ? Files.list(segments) : Stream.empty()) {
                assertEquals(List.of(), files.toList(), "no segment file while no block changed");
            }
        } finally {
            process.stop();
        }
    }

    /**
     * §19 「内存存储的挂地图分片不进目录模式」, the half inside the process: without a shard directory the
     * bounds are the release's and {@code EVT:shard_started} has no region and no capacity.
     */
    @Test
    public void aShardOutsideTheDirectoryModeStartsWithoutARegion() {
        final ServerProcess process = process();
        try {
            final Map<String, Object> started = mount(process, new OverlayOptions(16_384, 10)).world().startedAttributes();
            assertTrue(started.containsKey("region_id") && started.containsKey("capacity"), started.toString());
            assertNull(started.get("region_id"));
            assertNull(started.get("capacity"));
            assertEquals(0, started.get("flags_replayed"));
            assertEquals(0L, ((Number) started.get("flags_ms")).longValue());
        } finally {
            process.stop();
        }
    }

    /** 34 × 1 chunks in two region files: chunks 0 to 31 in r.0.0, 32 and 33 in r.1.0. */
    private static final SynxManifest.Bounds WIDE = new SynxManifest.Bounds(0, 0, 33, 0);

    /** Bakes the wide map under {@code mapsRoot}, map id {@code wide}. */
    private static ShardSettings bakeWide(Path mapsRoot) {
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, MapFixture.FLOOR, Block.STONE), 0, 64, WIDE), Registries.vanilla());
        final Baker.Result result = Baker.bake(new Baker.Request("wide", source, mapsRoot, 1, mapsRoot.resolve("logs"),
                Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        if (result.exitCode() != Baker.EXIT_PUBLISHED || result.directory() == null)
            throw new AssertionError("the wide map did not bake: " + result);
        return new ShardSettings(mapsRoot.resolve("wide"), result.directory().getFileName().toString(), 4, 100);
    }

    /**
     * The directory of the wide map: r0 its first 15 chunks, whose view (17 chunks past them, to chunk
     * 31) stays in r.0.0; r1 the rest.
     */
    private static ShardDirectory wideDirectory(String shardId) {
        return new ShardDirectory("wide", List.of(
                new ShardDirectory.Region("r0", new SynxManifest.Bounds(0, 0, 14, 0), List.of()),
                new ShardDirectory.Region("r1", new SynxManifest.Bounds(15, 0, 33, 0), List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 200),
                        new ShardDirectory.Channel("r1-c1", "r1", 1, "127.0.0.1", 25602, 150)), shardId);
    }

    /**
     * {@code API-M3-006} step (3): in the directory mode the start-up check covers the view of the region
     * of the shard, not the whole release. A damaged r.1.0 refuses the release and r1, not r0.
     */
    @Test
    public void theStartUpCheckCoversTheViewOfTheRegion(@TempDir Path maps) throws IOException {
        final ShardSettings wide = bakeWide(maps);
        final Path region = wide.buildDir().resolve("regions").resolve(SynxFormat.regionFileName(1, 0));
        final byte[] bytes = Files.readAllBytes(region);
        bytes[bytes.length - 100] ^= 0x01;
        Files.write(region, bytes);

        final ServerProcess r0 = process();
        try {
            assertNotNull(ShardWorld.prepare(r0, wide, SYNX, new OverlayOptions(16_384, 10), wideDirectory("r0-c1")));
        } finally {
            r0.stop();
        }
        for (ShardDirectory directory : Arrays.asList(null, wideDirectory("r1-c1"))) {
            final ServerProcess process = process();
            try {
                ShardWorld.prepare(process, wide, SYNX, new OverlayOptions(16_384, 10), directory);
                fail("the damaged r.1.0 is in the view of " + (directory == null ? "the release" : "r1"));
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().startsWith("synx-payload-hash: r.1.0.synx: "), e.getMessage());
            } finally {
                process.stop();
            }
        }
    }

    /**
     * {@code API-M3-010} 本分片 and {@code API-M3-006} step (5): the region of the shard is the bounds of
     * the map instance. The world border and the spawn point are the region's, a region that is not
     * square warns, the chunks past it are read only, and {@code EVT:shard_started} reads the one region
     * file its view maps, with the region and capacity of the shard.
     */
    @Test
    public void aShardInTheDirectoryModeServesItsRegion(@TempDir Path maps) throws IOException {
        final ShardSettings wide = bakeWide(maps);
        final ServerProcess process = process();
        final Logger logger = (Logger) LoggerFactory.getLogger(ShardWorld.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        try {
            final ShardWorld world = ShardWorld.prepare(process, wide, SYNX, new OverlayOptions(16_384, 10),
                    wideDirectory("r0-c1"));
            final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
            process.setShutdownParticipant(shutdown);
            appender.start();
            logger.addAppender(appender);
            final ShardWorld.Started started;
            try {
                started = world.start(shutdown, (_, _) -> {
                }, null, overlayRoot);
            } finally {
                logger.detachAppender(appender);
            }
            final InstanceContainer instance = started.instance();

            final SynxManifest.Bounds r0 = new SynxManifest.Bounds(0, 0, 14, 0);
            assertEquals(ShardWorld.worldBorder(r0), instance.getWorldBorder());
            assertEquals(120, instance.getWorldBorder().centerX());
            assertEquals(240, instance.getWorldBorder().diameter());
            assertEquals(new Pos(7 * 16 + 8.5, MapFixture.FLOOR, 8.5), started.spawn());
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("are not square")), appender.list.toString());

            process.dispatcher().start();
            assertFalse(instance.loadChunk(14, 0).join().isReadOnly(), "the last chunk of r0 takes changes");
            assertTrue(instance.loadChunk(15, 0).join().isReadOnly(), "the first chunk of r1 is read only here");
            assertTrue(instance.loadChunk(31, 0).join().isReadOnly());

            final Map<String, Object> attributes = world.startedAttributes();
            assertEquals(1, ((Number) attributes.get("regions")).intValue(), attributes.toString());
            assertEquals(Files.size(wide.buildDir().resolve("regions").resolve(SynxFormat.regionFileName(0, 0))),
                    ((Number) attributes.get("bytes_mapped")).longValue());
            assertEquals("r0", attributes.get("region_id"));
            assertEquals(200, attributes.get("capacity"));
        } finally {
            appender.stop();
            process.stop();
        }
    }

    /**
     * §14: an overlay store that cannot be opened refuses the start naming the overlay directory, which
     * is where RB-M3-002 has the operator look, not the map release.
     */
    @Test
    public void anOverlayStoreThatCannotBeOpenedNamesItsDirectory() throws IOException {
        final ServerProcess process = process();
        try {
            final Path notADirectory = Files.createFile(overlayRoot.resolve("overlay"));
            final ShardWorld world = ShardWorld.prepare(process, release, SYNX, new OverlayOptions(16_384, 10), null);
            final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
            try {
                world.start(shutdown, (_, _) -> {
                }, null, notADirectory);
                fail("the start is refused");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().startsWith("the overlay store " + notADirectory.resolve(release.mapBuild())
                        + " cannot be opened: "), e.getMessage());
            }
        } finally {
            process.stop();
        }
    }

    /** §19 「正常停机不丢覆盖层改动」, and 「最终落盘之后的改动不保证落盘」, the half with a shutdown task. */
    @Test
    public void aNormalStopWritesEveryChangeAndNothingAfterIt() throws Exception {
        final ServerProcess process = process();
        // Neither a periodic flush nor an unload writes a change before the stop, however slow the machine:
        // all 100 are the final flush's
        final OverlayOptions options = new OverlayOptions(16_384, 60);
        final Mounted mounted = mount(process, options);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        // Where plugins' onDisable runs, after the final flush
        process.scheduler().buildShutdownTask(() -> instance.setBlock(15 * 16 + 1, 50, 15 * 16 + 1, Block.GOLD_BLOCK));
        startTicking(process);
        final List<long[]> changed = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int chunkX = i % 16, chunkZ = i / 16;
            instance.loadChunk(chunkX, chunkZ).join();
            instance.setBlock(chunkX * 16 + 1, 50, chunkZ * 16 + 1, Block.GOLD_BLOCK);
            changed.add(new long[]{chunkX, chunkZ});
        }

        final List<String> log = synxLog(process::stop);

        assertTrue(log.stream().anyMatch(line -> line.startsWith("Overlay final flush: chunks=100 ")
                && line.contains("left=0") && line.endsWith("by=tick")), String.join("\n", log));
        final OverlayStore reopened = reopen(options);
        for (long[] chunk : changed) {
            final OverlayHit hit = reopened.find((int) chunk[0], (int) chunk[1]);
            assertNotNull(hit, "the change of " + chunk[0] + "," + chunk[1] + " is on disk");
            assertTrue(hit.generation() >= 1);
        }
        assertNull(reopened.find(15, 15), "a change after the final flush is not on disk");
    }

    /** §19 「脏区块卸载前先落盘」: grace 100, a changed chunk nobody holds a ticket on. */
    @Test
    public void aDirtyChunkIsWrittenBeforeItIsUnloaded() {
        final ServerProcess process = process();
        try {
            // No periodic flush within the test: the write before the unload is the one at tick 100
            final InstanceContainer instance = mount(process, new OverlayOptions(16_384, 60)).instance();
            process.dispatcher().start();
            instance.loadChunk(3, 3).join();
            instance.setBlock(3 * 16 + 1, 50, 3 * 16 + 1, Block.GOLD_BLOCK);

            tick(process, 100);
            assertNotNull(instance.getChunk(3, 3), "tick 100: dirty, it is written instead of unloaded");
            tick(process, 1);
            assertNull(instance.getChunk(3, 3), "tick 101: written, it is unloaded");

            instance.loadChunk(3, 3).join();
            assertEquals(Block.GOLD_BLOCK, instance.getBlock(3 * 16 + 1, 50, 3 * 16 + 1), "loaded again from the overlay record");
        } finally {
            process.stop();
        }
    }

    /** A dirty chunk as the overlay store sees it, encoding a record of the release. */
    private static final class Dirty implements OverlayChunk {
        private final SynxChunkRecord record;
        private volatile boolean dirty = true;

        Dirty(SynxChunkRecord record) {
            this.record = record;
        }

        @Override
        public int chunkX() {
            return record.chunkX();
        }

        @Override
        public int chunkZ() {
            return record.chunkZ();
        }

        @Override
        public long generation() {
            return 1;
        }

        @Override
        public boolean dirty() {
            return dirty;
        }

        @Override
        public @Nullable OverlayEncoding encodeOverlay(SynxSettings settings, long deadlineNanos) {
            return new OverlayEncoding(1, record);
        }

        @Override
        public boolean written(long generation, OverlayHit hit, boolean demote) {
            if (generation == 1) dirty = false;
            return true;
        }
    }

    private static Dirty dirty(int chunkX, int chunkZ) throws IOException, SynxException {
        final Path file = release.buildDir().resolve("regions").resolve(SynxFormat.regionFileName(0, 0));
        try (Arena arena = Arena.ofConfined()) {
            final SynxSlot.Present present = (SynxSlot.Present) SynxRegion.open(file, arena).slot(chunkX, chunkZ);
            final SynxChunkRecord region = present.record();
            // An overlay record carries no segment ④
            return new Dirty(new SynxChunkRecord(chunkX, chunkZ, region.packet(), region.navigation(),
                    region.blockEntities(), region.staticEntities(), new byte[0]));
        }
    }

    /**
     * §19 「真停机序列先 true 后 false 只落盘一次」: the step is called for a live tick, whose write phase
     * never runs, then for a dead one; the final flush runs once, on the shutdown thread.
     */
    @Test
    public void aLiveThenDeadSaveFlushesOnceOnTheShutdownThread() throws Exception {
        final OverlayOptions options = new OverlayOptions(16_384, 10);
        final OverlayStore overlay = reopen(options);
        for (int i = 0; i < 5; i++) {
            final Dirty chunk = dirty(i, 0);
            overlay.materialized(chunk);
            overlay.modified(chunk);
        }
        final ServerProcess process = process();
        try {
            final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
            final List<Boolean> calls = new CopyOnWriteArrayList<>();
            shutdown.addAfterPlayers("overlay", (remaining, tickAlive) -> {
                calls.add(tickAlive);
                return overlay.finalFlush(remaining, tickAlive);
            });

            final List<String> log = synxLog(() -> {
                final CompletableFuture<?> live = shutdown.saveAll(Duration.ofSeconds(30), true);
                final CompletableFuture<?> dead = shutdown.saveAll(Duration.ofSeconds(15), false);
                assertTrue(dead.isDone(), "the dead save runs on this thread");
                assertTrue(live.isDone(), "one future stands for the whole save");
            });

            assertEquals(List.of(true, false), calls);
            final OverlayStats stats = overlay.stats();
            assertEquals(5, stats.flushAppended());
            assertEquals(1, stats.flushFsyncs());
            assertTrue(log.stream().anyMatch(line -> line.startsWith("Overlay final flush: chunks=5 ")
                    && line.endsWith("by=shutdown")), String.join("\n", log));
            assertEquals(5, reopen(options).stats().records());
        } finally {
            process.stop();
        }
    }
}
