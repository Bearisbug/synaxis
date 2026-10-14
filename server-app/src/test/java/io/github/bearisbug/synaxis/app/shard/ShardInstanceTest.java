package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxChunk;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map instance of a shard, M3-S ({@code docs/DESIGN-M3.md} {@code REQ-M3-029}): the chunks asked for
 * outside {@code view_bounds}, the light of the air chunks the engine builds, and what the start says: of a
 * map players may change, of the settings in effect and of a spawn column without ground.
 */
public class ShardInstanceTest {

    @TempDir
    static Path baked;
    private static ShardSettings release;

    @TempDir
    Path overlayRoot;

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(baked);
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

    private record Started(ShardWorld world, ShardWorld.Started started, List<ILoggingEvent> log) {
    }

    /** Steps (3) and (5) of {@code Main} on the fixture map, the lines of {@code ShardWorld} and {@code ShardInstance} kept. */
    private Started start(ServerProcess process, SynxOptions options, ListAppender<ILoggingEvent> appender) {
        return start(process, release, options, new OverlayOptions(16_384, 10), appender);
    }

    /** {@link #start(ServerProcess, SynxOptions, ListAppender)} on another map and other overlay settings. */
    private Started start(ServerProcess process, ShardSettings map, SynxOptions options, OverlayOptions overlay,
                          ListAppender<ILoggingEvent> appender) {
        final ShardWorld world = ShardWorld.prepare(process, map, options, overlay, null);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final ShardWorld.Started started = world.start(shutdown, (_, _) -> {
        }, null, overlayRoot);
        return new Started(world, started, appender.list);
    }

    private static ServerProcess process() {
        return ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).chunkViewDistance(12).build());
    }

    private static ListAppender<ILoggingEvent> listen(Class<?>... types) {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Class<?> type : types) ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    private static void stopListening(ListAppender<ILoggingEvent> appender, Class<?>... types) {
        for (Class<?> type : types) ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    /** Whether the stack of a logged throwable runs through {@code method} of this class. */
    private static boolean through(IThrowableProxy throwable, String method) {
        return Arrays.stream(throwable.getStackTraceElementProxyArray()).map(StackTraceElementProxy::getStackTraceElement)
                .anyMatch(frame -> frame.getClassName().equals(ShardInstanceTest.class.getName())
                        && frame.getMethodName().equals(method));
    }

    /**
     * §19 「view_bounds 外的区块请求被计数并带请求方的栈」: a chunk asked for outside {@code view_bounds} counts,
     * and the first WARN carries the stack of the thread that asked, here this test; the next one has none.
     */
    @Test
    public void aChunkAskedForOutsideTheViewBoundsCountsWithTheStackOfWhoAsked() {
        final ServerProcess process = process();
        final ListAppender<ILoggingEvent> appender = listen(ShardInstance.class);
        try {
            final Started started = start(process, new SynxOptions(false, 1 << 20, 1, false), appender);
            final ShardInstance instance = assertInstanceOf(ShardInstance.class, started.started().instance());
            assertEquals(0, instance.voidChunks());
            instance.loadChunk(200, 0).join();
            assertEquals(1, instance.voidChunks());
            instance.loadChunk(201, 0).join();
            assertEquals(2, instance.voidChunks());

            final List<ILoggingEvent> lines = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Void chunk requested at ")).toList();
            assertTrue(lines.getFirst().getFormattedMessage().startsWith("Void chunk requested at 200 0: outside the view "
                    + "bounds "), lines.getFirst().getFormattedMessage());
            final IThrowableProxy stack = lines.getFirst().getThrowableProxy();
            assertNotNull(stack, "the first one carries a stack");
            assertTrue(through(stack, "aChunkAskedForOutsideTheViewBoundsCountsWithTheStackOfWhoAsked"),
                    "the stack is the requester's: this test");
            assertTrue(lines.stream().skip(1).allMatch(line -> line.getThrowableProxy() == null), "the later ones have none");
            instance.loadChunk(5, 5).join();
            assertEquals(2, instance.voidChunks(), "a chunk of the map does not count");
        } finally {
            stopListening(appender, ShardInstance.class);
            process.stop();
        }
    }

    /**
     * §19 「view_bounds 外的区块请求被计数并带请求方的栈」, two requests at once: the budget of the later lines
     * already taken by the other one, the first line still carries the stack.
     */
    @Test
    public void theFirstVoidChunkLineKeepsItsStackWhenARequestAtTheSameTimeTookTheTurn() {
        final ServerProcess process = process();
        final ListAppender<ILoggingEvent> appender = listen(ShardInstance.class);
        try {
            final Started started = start(process, new SynxOptions(false, 1 << 20, 1, false), appender);
            final ShardInstance instance = assertInstanceOf(ShardInstance.class, started.started().instance());
            // The other request, past the stack line's check but first to the budget
            instance.voidRequests.warn("Another request");
            instance.loadChunk(200, 0).join();
            final List<ILoggingEvent> lines = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Void chunk requested at ")).toList();
            assertEquals(1, lines.size(), "the stack line is written: " + appender.list);
            assertNotNull(lines.getFirst().getThrowableProxy(), "with its stack");
        } finally {
            stopListening(appender, ShardInstance.class);
            process.stop();
        }
    }

    /**
     * §19 「地图外缘与 view_bounds 外的空气区块满天光」: where the loader has no record, at the edge of the map
     * and outside {@code view_bounds}, the engine builds an air chunk whose every section has sky light 15.
     */
    @Test
    public void theAirChunksAtTheEdgeOfTheMapAndPastTheViewBoundsAreLitByTheSky() {
        final ServerProcess process = process();
        final ListAppender<ILoggingEvent> appender = listen(ShardInstance.class);
        try {
            final Started started = start(process, new SynxOptions(false, 1 << 20, 1, false), appender);
            final ShardInstance instance = assertInstanceOf(ShardInstance.class, started.started().instance());
            final byte[] full = new byte[2048];
            Arrays.fill(full, (byte) 0xFF);
            // Inside view_bounds west of the bounds 0..15, without the region file r.-1.0; then outside view_bounds
            for (int[] at : new int[][]{{-1, 3}, {200, 0}}) {
                final Chunk chunk = instance.loadChunk(at[0], at[1]).join();
                assertFalse(chunk instanceof SynxChunk, Arrays.toString(at));
                final LightData light = ((ChunkDataPacket) ((CachedPacket) chunk.getFullDataPacket()).packet()).lightData();
                assertTrue(light.emptySkyMask().isEmpty(), Arrays.toString(at) + ": no section without sky light, "
                        + light.emptySkyMask());
                assertEquals(chunk.getSections().size(), light.skyLight().size(), Arrays.toString(at));
                for (byte[] sky : light.skyLight()) assertArrayEquals(full, sky, Arrays.toString(at) + ": sky light 15");
            }
        } finally {
            stopListening(appender, ShardInstance.class);
            process.stop();
        }
    }

    /**
     * §19 「挂地图分片默认不能改地形」, the start: {@code EVT:shard_started} carries {@code world_writable}, and a
     * map players may change logs a WARN.
     */
    @Test
    public void aMapPlayersMayChangeIsWarnedAboutAtTheStart() {
        for (boolean writable : new boolean[]{false, true}) {
            final ServerProcess process = process();
            final ListAppender<ILoggingEvent> appender = listen(ShardWorld.class);
            try {
                final Started started = start(process, new SynxOptions(writable, 1 << 20, 1, false), appender);
                final Map<String, Object> attributes = started.world().startedAttributes();
                assertEquals(writable, attributes.get("world_writable"));
                final List<String> warnings = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(line -> line.startsWith("Map ") && line.contains(" is writable")).toList();
                if (writable) {
                    assertEquals(List.of("Map " + release.mapBuild() + " is writable (synaxis.world-writable=true): "
                            + "players' digging and placing go into the overlay"), warnings);
                } else {
                    assertEquals(List.of(), warnings, "no WARN for a map nobody changes");
                }
            } finally {
                stopListening(appender, ShardWorld.class);
                process.stop();
            }
        }
    }

    /**
     * §19 「挂地图的分片起服」, the settings: {@code Map mounted} names the six overlay and light settings in
     * effect, {@code EVT:shard_started} the three that §21 and {@code RB-M3-003} read.
     */
    @Test
    public void theStartNamesTheSettingsInEffect() {
        final ServerProcess process = process();
        final ListAppender<ILoggingEvent> appender = listen(ShardWorld.class);
        try {
            final Started started = start(process, release, new SynxOptions(false, 3 << 20, 3, true),
                    new OverlayOptions(1_000, 7, 123_456_789), appender);
            final Map<String, Object> attributes = started.world().startedAttributes();
            assertEquals(1_000, attributes.get("overlay_cap"));
            assertEquals(123_456_789L, attributes.get("overlay_max_bytes"));
            assertEquals(3, attributes.get("relight_threads"));
            assertEquals(0L, ((Number) attributes.get("overlay_records")).longValue());
            final List<String> mounted = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.startsWith("Map mounted: ")).toList();
            assertEquals(1, mounted.size(), mounted.toString());
            assertTrue(mounted.getFirst().endsWith(" overlay_cap=1000 flush_interval_s=7 overlay_max_bytes=123456789 "
                    + "relight_threads=3 resend_light=true section_cache_bytes=3145728"), mounted.getFirst());
        } finally {
            stopListening(appender, ShardWorld.class);
            process.stop();
        }
    }

    /**
     * §19 「出生列整列是空气时起服记 WARN」: a release whose spawn column has nothing to stand on starts, the
     * spawn point at the bottom of the world, with one WARN; the fixture map, ground at y 40, has none.
     */
    @Test
    public void aSpawnColumnOfAirIsWarnedAboutAtTheStart(@TempDir Path maps) {
        final Baker.Result air = Baker.bake(new Baker.Request("air", new GeneratorSource(new GeneratorPreset(_ -> {
        }, 0, 64, new SynxManifest.Bounds(0, 0, 0, 0)), Registries.vanilla()), maps, 1, maps.resolve("logs"),
                Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        assertEquals(Baker.EXIT_PUBLISHED, air.exitCode(), air.toString());
        final ShardSettings airMap = new ShardSettings(maps.resolve("air"),
                Objects.requireNonNull(air.directory()).getFileName().toString(), 4, 100);
        for (boolean overAir : new boolean[]{false, true}) {
            final ServerProcess process = process();
            final ListAppender<ILoggingEvent> appender = listen(ShardWorld.class);
            try {
                final Started started = start(process, overAir ? airMap : release, new SynxOptions(false, 1 << 20, 1, false),
                        new OverlayOptions(16_384, 10), appender);
                final List<String> warnings = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(line -> line.startsWith("The center column ")).toList();
                if (overAir) {
                    assertEquals(0, started.started().spawn().y(), "the bottom of the world");
                    assertEquals(List.of("The center column 8,8 of " + airMap.mapBuild() + " has no block to stand on, "
                            + "players spawn at the bottom of the world, y 0"), warnings);
                } else {
                    assertEquals(MapFixture.FLOOR, started.started().spawn().y());
                    assertEquals(List.of(), warnings, "ground to stand on");
                }
            } finally {
                stopListening(appender, ShardWorld.class);
                process.stop();
            }
        }
    }
}
