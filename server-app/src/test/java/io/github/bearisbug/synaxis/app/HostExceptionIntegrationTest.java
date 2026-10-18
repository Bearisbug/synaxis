package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's exception sink of {@code docs/DESIGN-PROD.md} {@code API-PROD-013}
 * ({@code REQ-PROD-015}), installed the way {@link Main} installs it.
 */
@EnvTest
class HostExceptionIntegrationTest {

    private static final Pattern TICK = Pattern.compile("tick=(\\d+)");

    @TempDir
    Path workingDirectory;

    private Thread.UncaughtExceptionHandler previousUncaught;

    @BeforeEach
    void rememberUncaughtHandler() {
        previousUncaught = Thread.getDefaultUncaughtExceptionHandler();
    }

    @AfterEach
    void restoreUncaughtHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousUncaught);
    }

    private HostExceptionHandler install(Env env) {
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"),
                new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY));
        return Main.installExceptionHandler(env.process(),
                Main.crashReports(env.process(), host, "r01-c1", workingDirectory.resolve("crash-reports")));
    }

    // §19: "未捕获异常走 SLF4J 并写崩溃报告", first half
    @Test
    void aListenerThatThrowsOnEveryPacketIsOneLineWithTheTickAndTheConnection(Env env) {
        install(env);
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 40, 0));
        final AtomicInteger calls = new AtomicInteger();
        env.process().packetListener().setPlayListener(ClientPluginMessagePacket.class, (_, _) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("listener failure");
        });

        try (LogCapture logs = LogCapture.of(HostExceptionHandler.class)) {
            // The queue is drained the way the player's own tick drains it, three packets a tick
            for (int tick = 0; tick < 3; tick++) {
                env.tick();
                for (int packet = 0; packet < 3; packet++)
                    player.addPacketToQueue(new ClientPluginMessagePacket("synaxis:test", new byte[0]));
                player.interpretPacketQueue();
            }
            assertEquals(9, calls.get());

            final List<ILoggingEvent> errors = logs.eventsAt(Level.ERROR);
            assertEquals(1, errors.size(), "nine failures of one kind inside 10 s are one line");
            final ILoggingEvent line = errors.getFirst();
            final String message = line.getFormattedMessage();
            assertTrue(message.contains("connection_id=" + player.getPlayerConnection().connectionId()), message);
            final Matcher tick = TICK.matcher(message);
            assertTrue(tick.find(), message);
            assertEquals(1, Long.parseLong(tick.group(1)), "the first failure happened in the first tick");
            assertNotNull(line.getThrowableProxy(), "the line carries the stack");
        }
    }

    // §19: "未捕获异常走 SLF4J 并写崩溃报告", second half, and "OOM 不走完整 stop"
    @Test
    void aFatalErrorWritesOneCrashReportAndDoesNotStopTheProcess(Env env) throws IOException {
        install(env);
        final AtomicBoolean stopSequenceRan = new AtomicBoolean();
        env.process().scheduler().buildShutdownTask(() -> stopSequenceRan.set(true));

        try (LogCapture logs = LogCapture.of(HostExceptionHandler.class)) {
            env.process().exception().handleException(new OutOfMemoryError("test heap"));
            env.process().exception().handleException(new OutOfMemoryError("test heap again"));

            assertEquals(1, logs.eventsAt(Level.ERROR).size());
            assertTrue(logs.messagesAt(Level.ERROR).getFirst().contains("crash_report=" + workingDirectory),
                    logs.messagesAt(Level.ERROR).getFirst());
        }
        final List<Path> files;
        try (Stream<Path> listing = Files.list(workingDirectory.resolve("crash-reports"))) {
            files = listing.toList();
        }
        assertEquals(1, files.size(), "the first fatal error is the one the report is about");
        assertTrue(files.getFirst().getFileName().toString().matches("\\d{8}-\\d{6}-r01-c1\\.txt"), files.toString());
        final String report = Files.readString(files.getFirst());
        assertTrue(report.contains("java.lang.OutOfMemoryError: test heap"), report);
        assertTrue(report.contains("ServerConfig["), report);
        assertTrue(report.contains("-- Thread dump --"), report);
        assertTrue(HostExceptionHandler.isFatal(new InternalError()));
        assertFalse(HostExceptionHandler.isFatal(new StackOverflowError()), "a stack overflow is survivable");
        assertFalse(stopSequenceRan.get(), "the stop sequence is not the answer to an exhausted heap");
    }

    // A scheduled task hands over its own throwable: what it throws decides whether the error is fatal
    @Test
    void aFatalErrorThrownByAScheduledTaskWritesACrashReport(Env env) throws IOException {
        install(env);
        env.process().scheduler().scheduleNextTick(() -> {
            throw new InternalError("task failure");
        });

        try (LogCapture logs = LogCapture.of(HostExceptionHandler.class)) {
            for (int tick = 0; tick < 3; tick++) env.tick();

            assertEquals(1, logs.eventsAt(Level.ERROR).size());
            assertTrue(logs.messagesAt(Level.ERROR).getFirst().startsWith("Fatal error"),
                    logs.messagesAt(Level.ERROR).getFirst());
        }
        final Path reports = workingDirectory.resolve("crash-reports");
        assertTrue(Files.isDirectory(reports), "a fatal error thrown by a task writes a crash report");
        final List<Path> files;
        try (Stream<Path> listing = Files.list(reports)) {
            files = listing.toList();
        }
        assertEquals(1, files.size());
        final String report = Files.readString(files.getFirst());
        assertTrue(report.contains("java.lang.InternalError: task failure"), report);
    }

    // ... and what it throws decides the budget: one kind of failing task does not hide another
    @Test
    void twoKindsOfFailingTasksAreTwoLinesWithTheirStacks(Env env) {
        install(env);
        env.process().scheduler().scheduleNextTick(() -> {
            throw new IllegalStateException("first task failure");
        });
        env.process().scheduler().scheduleNextTick(() -> {
            throw new NullPointerException("second task failure");
        });

        try (LogCapture logs = LogCapture.of(HostExceptionHandler.class)) {
            for (int tick = 0; tick < 3; tick++) env.tick();

            final List<ILoggingEvent> errors = logs.eventsAt(Level.ERROR);
            final Set<String> thrown = errors.stream()
                    .map(line -> line.getThrowableProxy() == null ? "-" : line.getThrowableProxy().getClassName())
                    .collect(Collectors.toSet());
            assertEquals(Set.of(IllegalStateException.class.getName(), NullPointerException.class.getName()), thrown,
                    "each kind of failure gets its own first line with its stack");
            assertEquals(2, errors.size());
        }
    }

    @Test
    void aThreadThatDiesOfAThrowableIsLoggedOnThatThread(Env env) throws InterruptedException {
        install(env);

        try (LogCapture logs = LogCapture.of(HostExceptionHandler.class)) {
            final Thread thread = Thread.ofPlatform().name("dying-thread").start(() -> {
                throw new IllegalArgumentException("uncaught");
            });
            thread.join();

            final List<ILoggingEvent> errors = logs.eventsAt(Level.ERROR);
            assertEquals(1, errors.size());
            assertEquals("dying-thread", errors.getFirst().getThreadName());
            assertTrue(errors.getFirst().getFormattedMessage().contains("connection_id=-"),
                    errors.getFirst().getFormattedMessage());
        }
    }
}
