package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        final ShardWorld world = ShardWorld.prepare(process, release, SYNX, overlay);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final List<String> events = new CopyOnWriteArrayList<>();
        return new Mounted(world, world.start(shutdown, (name, _) -> events.add(name), overlayRoot), events);
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
     * §14: an overlay store that cannot be opened refuses the start naming the overlay directory, which
     * is where RB-M3-002 has the operator look, not the map release.
     */
    @Test
    public void anOverlayStoreThatCannotBeOpenedNamesItsDirectory() throws IOException {
        final ServerProcess process = process();
        try {
            final Path notADirectory = Files.createFile(overlayRoot.resolve("overlay"));
            final ShardWorld world = ShardWorld.prepare(process, release, SYNX, new OverlayOptions(16_384, 10));
            final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
            try {
                world.start(shutdown, (_, _) -> {
                }, notADirectory);
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
