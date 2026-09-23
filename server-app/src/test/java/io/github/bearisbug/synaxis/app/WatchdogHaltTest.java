package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A process whose tick is stuck is halted ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-014}): the
 * halt ends the JVM, so the stuck process is a JVM of its own, {@link WatchdogHaltFixture}.
 * <p>
 * Judging the process dead does not save anything yet: the save sequence it is meant to start
 * comes with wave 3, this test only covers the halt path.
 */
public class WatchdogHaltTest {

    private record Line(long nanos, String text) {
    }

    @Test
    public void aProcessWhoseTickIsStuckWritesACrashReportAndIsHaltedWithSeventy() throws Exception {
        final Path directory = Files.createTempDirectory("synaxis-watchdog-");
        final Path reports = directory.resolve("crash-reports");
        final Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "-Dsynaxis.watchdog.early-warning-ms=500",
                "-Dsynaxis.watchdog.early-warning-every-ms=1000",
                "-Dsynaxis.watchdog.timeout-ms=3000",
                "-Dsynaxis.watchdog.halt-after-ms=2000",
                WatchdogHaltFixture.class.getName(), reports.toString())
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        final List<Line> output = new CopyOnWriteArrayList<>();
        final Thread reader = Thread.ofPlatform().start(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) output.add(new Line(System.nanoTime(), line));
            } catch (IOException _) {
                // the JVM is gone, what it wrote is in `output`
            }
        });
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the stuck JVM is halted");
            final long exited = System.nanoTime();
            reader.join(TimeUnit.SECONDS.toMillis(10));
            final String log = String.join("\n", output.stream().map(Line::text).toList());

            assertEquals(70, process.exitValue(), log);
            assertTrue(log.contains("Tick watchdog on: stacks after 500 ms and every 1000 ms after that, judged dead "
                    + "after 3000 ms, halted with exit code 70 2000 ms later"), "the durations came from the "
                    + "synaxis.watchdog.* properties: " + log);
            assertTrue(log.contains(WatchdogHaltFixture.STALLING), log);
            assertTrue(log.contains("Tick watchdog: the tick has been running for"), "an early warning: " + log);
            assertTrue(log.contains("\"Syn-TickScheduler\""), "with the stack of the tick scheduler: " + log);
            assertTrue(log.contains("\"Syn-Tick-0\""), "and of the tick threads: " + log);
            assertTrue(log.contains("WatchdogHaltFixture.stall"), "down to the frame it is stuck in: " + log);
            assertTrue(log.contains("halting the process with exit code 70"), log);

            final long judgedDead = output.stream().filter(line -> line.text().contains("is judged dead"))
                    .mapToLong(Line::nanos).findFirst().orElseThrow(() -> new AssertionError("never judged dead: " + log));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(exited - judgedDead) >= 1_500,
                    "the halt waits synaxis.watchdog.halt-after-ms after the verdict, waited "
                            + TimeUnit.NANOSECONDS.toMillis(exited - judgedDead) + " ms");

            final List<Path> written;
            try (Stream<Path> files = Files.list(reports)) {
                written = files.toList();
            }
            assertEquals(1, written.size(), "one crash report: " + written);
            assertTrue(written.getFirst().getFileName().toString().endsWith("-watchdog-test.txt"), written.toString());
            final String report = Files.readString(written.getFirst());
            assertTrue(report.contains("Reason: tick watchdog: the tick has been stuck for "), report);
            assertTrue(report.contains("-- Thread dump --"), report);
            assertTrue(report.contains("WatchdogHaltFixture.stall"), report);
        } finally {
            process.destroyForcibly();
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
