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
import io.github.bearisbug.synaxis.synx.OverlayOptions;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map instance of a shard, M3-S ({@code docs/DESIGN-M3.md} {@code REQ-M3-029}): the chunks asked for
 * outside {@code view_bounds}, and what the start says of a map players may change.
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
        final ShardWorld world = ShardWorld.prepare(process, release, options, new OverlayOptions(16_384, 10), null);
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
}
