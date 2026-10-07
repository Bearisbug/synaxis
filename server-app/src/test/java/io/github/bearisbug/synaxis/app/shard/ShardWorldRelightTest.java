package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.OverlayHit;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.OverlayStats;
import io.github.bearisbug.synaxis.synx.OverlayStore;
import io.github.bearisbug.synaxis.synx.RelightStats;
import io.github.bearisbug.synaxis.synx.SynxChunkPacket;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxStreamStats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime light of a map shard assembled as {@code Main} assembles it ({@code docs/DESIGN-M3.md}
 * §19, the server-app entry of M3-D; {@code REQ-M3-012}): the shutdown step {@code relight} before
 * {@code overlay}, and the light counters of {@code EVT:chunk_stream_stats}.
 */
@Timeout(60)
public class ShardWorldRelightTest {
    private static final SynxOptions SYNX = new SynxOptions(true, 1 << 24, 1, false);

    @TempDir
    static Path baked;
    private static ShardSettings release;
    private static SynxManifest manifest;

    @TempDir
    Path overlayRoot;

    @BeforeAll
    static void bake() throws Exception {
        release = MapFixture.bake(baked);
        manifest = SynxManifest.fromJson(Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
    }

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

    /** Hands every task to a thread of its own that waits for the gate to open. */
    private static final class Gate implements Executor {
        final CountDownLatch open = new CountDownLatch(1);
        volatile long openedAt;

        @Override
        public void execute(Runnable command) {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    open.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
                command.run();
            });
        }

        void openIn(Duration delay) {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                openedAt = System.nanoTime();
                open.countDown();
            });
        }
    }

    /**
     * 场景「停机时先排空重算再最终落盘」: the recomputation of a change made right before the stop
     * waits behind a gate that opens 100 ms into the stop; the step {@code relight} waits for it and its
     * install, and only then does the final flush write the chunk, with the new light.
     */
    @Test
    public void theStopDrainsTheRelightBeforeTheFinalFlush() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                .chunkViewDistance(12).build());
        final ShardWorld world = ShardWorld.prepare(process, release, SYNX, new OverlayOptions(16_384, 60), null);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final Gate gate = new Gate();
        final ShardWorld.Started started = world.start(shutdown, (_, _) -> {
        }, null, overlayRoot, gate);
        final InstanceContainer instance = started.instance();
        instance.setChunkUnloadGraceTicks(-1);
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50), process.config().maxTickCatchUp()).start();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) instance.loadChunk(x, z).join();
        }
        final int x = 3 * 16 + 8, y = MapFixture.FLOOR, z = 3 * 16 + 8;

        final Logger logger = (Logger) LoggerFactory.getLogger("io.github.bearisbug.synaxis.synx");
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        logger.addAppender(log);
        final long stopAt;
        try {
            instance.setBlock(x, y, z, Block.GLOWSTONE);
            stopAt = System.nanoTime();
            gate.openIn(Duration.ofMillis(100));
            process.stop();
        } finally {
            logger.detachAppender(log);
            log.stop();
        }
        final List<String> lines = log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        final int drained = indexOf(lines, "Relight drain:");
        final int flushed = indexOf(lines, "Overlay final flush:");
        assertTrue(drained >= 0 && flushed > drained, "relight before overlay: " + lines);
        assertTrue(gate.openedAt - stopAt >= TimeUnit.MILLISECONDS.toNanos(100), "the gate opened 100 ms into the stop");
        assertTrue(lines.get(drained).contains("left=0"), lines.get(drained));

        final OverlayStore reopened = OverlayStore.open(overlayRoot, manifest, ShardWorld.overlaySettings(manifest, 256),
                new OverlayOptions(16_384, 60));
        final OverlayHit hit = reopened.find(3, 3);
        assertNotNull(hit, "3,3 is in the final flush");
        final ChunkDataPacket written = SynxChunkPacket.decode(hit.record().packet().toArray(ValueLayout.JAVA_BYTE),
                Registries.vanilla());
        final LightResult truth = LightTruth.of(instance, 3, 3);
        assertNull(LightTruth.firstDifference(written.lightData(), truth, 3, 3, 0, 4),
                "the light of the final flush's record is the server's");
    }

    /**
     * 场景「停机排空不受装回上限约束」: 100 changes right before the stop, each the center of a
     * recomputation of its own; the 100 are computed before the tick starts, so that 900 target
     * entries wait for the install when the stop begins. With the tick alive the install at the end of
     * the tick handles them past 64 a tick and 1 ms: the step {@code relight} is done within its 500 ms
     * with none left, and the final flush writes every changed chunk with the new light.
     */
    @Test
    public void aStopDrainsNineHundredTargetsWithinItsBudget() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                .chunkViewDistance(12).build());
        final ShardWorld world = ShardWorld.prepare(process, release, SYNX, new OverlayOptions(16_384, 60), null);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        // A thread a task and no backlog: all 100 are computed while no tick installs
        final Gate gate = new Gate();
        final ShardWorld.Started started = world.start(shutdown, (_, _) -> {
        }, null, overlayRoot, gate);
        final InstanceContainer instance = started.instance();
        instance.setChunkUnloadGraceTicks(-1);
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        // The 5×5 of every center
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 8; z++) instance.loadChunk(x, z).join();
        }
        for (int i = 0; i < 100; i++) instance.setBlock((i % 16) * 16 + 1, 50, (i / 16) * 16 + 1, Block.GOLD_BLOCK);
        gate.openIn(Duration.ZERO);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        RelightStats computed = started.relight().stats();
        while (computed.relights() < 100) {
            assertTrue(System.nanoTime() < deadline, "the 100 recomputations are done within 30 s: " + computed);
            Thread.sleep(10);
            computed = started.relight().stats();
        }
        assertEquals(100, computed.pending(), "100 centers computed, none installed: 900 target entries wait");

        final Logger logger = (Logger) LoggerFactory.getLogger("io.github.bearisbug.synaxis.synx");
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        logger.addAppender(log);
        try {
            new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50), process.config().maxTickCatchUp()).start();
            process.stop();
        } finally {
            logger.detachAppender(log);
            log.stop();
        }
        final List<String> lines = log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        final int drained = indexOf(lines, "Relight drain");
        assertTrue(drained >= 0 && lines.get(drained).startsWith("Relight drain: ")
                && lines.get(drained).endsWith(" ms, left=0"), "drained within 500 ms, none left: " + lines);
        final int flushed = indexOf(lines, "Overlay final flush: chunks=100 ");
        assertTrue(flushed > drained && lines.get(flushed).endsWith("by=tick"), "then the final flush of the tick: " + lines);

        final OverlayStore reopened = OverlayStore.open(overlayRoot, manifest, ShardWorld.overlaySettings(manifest, 256),
                new OverlayOptions(16_384, 60));
        for (int i = 0; i < 100; i++) {
            final int chunkX = i % 16, chunkZ = i / 16;
            final OverlayHit hit = reopened.find(chunkX, chunkZ);
            assertNotNull(hit, chunkX + "," + chunkZ + " is in the final flush");
            final ChunkDataPacket written = SynxChunkPacket.decode(hit.record().packet().toArray(ValueLayout.JAVA_BYTE),
                    Registries.vanilla());
            assertNull(LightTruth.firstDifference(written.lightData(), LightTruth.of(instance, chunkX, chunkZ), chunkX,
                    chunkZ, 0, 4), "the light of the record of " + chunkX + "," + chunkZ + " is the server's");
        }
    }

    private static int indexOf(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(prefix)) return i;
        return -1;
    }

    /** §20: the nine light attributes of {@code EVT:chunk_stream_stats}, counters as differences. */
    @Test
    public void theStreamStatisticsCarryTheNineLightAttributes() {
        final Map<String, Object> event = ShardWorld.streamStats(
                new SynxStreamStats(0, 0, 0, 0, 0, 0, 0, 0, 0), new SynxStreamStats(0, 0, 0, 0, 0, 0, 0, 0, 0),
                new OverlayStats(0, 0, 0, 0, 0, 0, 0, 0, 0, false), new OverlayStats(0, 0, 0, 0, 0, 0, 0, 0, 0, false),
                new RelightStats(10, 2, 1, 5, 40, 0, 0, 0, 0),
                new RelightStats(25, 3, 4, 9, 76, 180.5, 12.25, 3.5, 7), 0);
        assertEquals(15L, event.get("relights"));
        assertEquals(1L, event.get("relight_skipped"));
        assertEquals(3L, event.get("light_materialized"));
        assertEquals(4L, event.get("light_packets"));
        assertEquals(36L, event.get("light_sections"));
        assertEquals(180.5, event.get("relight_queue_p99_ms"));
        assertEquals(12.25, event.get("relight_ms_p99"));
        assertEquals(3.5, event.get("relight_install_ms_max"));
        assertEquals(7, event.get("relight_pending"));
    }
}
