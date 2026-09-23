package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crash report writer of {@code docs/DESIGN-PROD.md} {@code API-PROD-013}
 * ({@code REQ-PROD-015}), which the tick watchdog of {@code REQ-PROD-014} writes through as well.
 */
class CrashReportsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T04:05:06Z"), ZoneOffset.UTC);

    private static CrashReports reports(Path directory) {
        return new CrashReports(directory, "r01-c1", CLOCK, () -> "synaxis.app.port=25601\n",
                () -> List.of("rts 0.1.0"));
    }

    @Test
    void aReportCarriesTheReasonTheCauseThePluginsTheConfigurationAndEveryThread(@TempDir Path directory)
            throws IOException {
        final Path file = reports(directory).write("fatal error: test", new IllegalStateException("the cause"));

        assertNotNull(file);
        assertEquals(directory.resolve("20260923-040506-r01-c1.txt"), file);
        final String report = Files.readString(file);
        assertTrue(report.contains("Reason: fatal error: test"), report);
        assertTrue(report.contains("java.lang.IllegalStateException: the cause"), report);
        assertTrue(report.contains("-- Git head --\n"), report);
        assertTrue(report.contains("-- Plugins --\nrts 0.1.0\n"), report);
        assertTrue(report.contains("-- Effective configuration --\nsynaxis.app.port=25601\n"), report);
        // The thread writing the report is in its own dump, with more than the eight frames
        // ThreadInfo#toString would have kept
        final String dump = report.substring(report.indexOf("-- Thread dump --"));
        final String self = dump.substring(dump.indexOf('"' + Thread.currentThread().getName() + '"'));
        final String selfFrames = self.substring(0, self.indexOf("\n\n"));
        assertTrue(selfFrames.lines().filter(line -> line.startsWith("\tat ")).count() > 8, selfFrames);
    }

    @Test
    void aSecondReportInTheSameSecondDoesNotOverwriteTheFirst(@TempDir Path directory) {
        final CrashReports reports = reports(directory);

        final Path first = reports.write("first", null);
        final Path second = reports.write("second", null);

        assertEquals(directory.resolve("20260923-040506-r01-c1.txt"), first);
        assertEquals(directory.resolve("20260923-040506-r01-c1-2.txt"), second);
    }

    @Test
    void aSectionThatFailsDoesNotCostTheOthers(@TempDir Path directory) throws IOException {
        final CrashReports reports = new CrashReports(directory, "r01-c1", CLOCK, () -> "config\n", () -> {
            throw new IllegalStateException("plugins are being enabled");
        });

        final Path file = reports.write("watchdog", null);

        assertNotNull(file);
        final String report = Files.readString(file);
        assertTrue(report.contains("(could not be collected: java.lang.IllegalStateException: plugins are being enabled)"),
                report);
        assertTrue(report.contains("-- Thread dump --"), report);
    }

    @Test
    void aReportThatCannotBeWrittenIsOneLineAndNoThrow(@TempDir Path directory) throws IOException {
        final Path blocked = directory.resolve("crash-reports");
        Files.writeString(blocked, "not a directory");

        final CrashReports reports = reports(blocked);

        try (LogCapture logs = LogCapture.of(CrashReports.class)) {
            assertNull(reports.write("first", null));
            assertNull(reports.write("second", null));

            assertEquals(1, logs.eventsAt(Level.ERROR).size(), "an unwritable directory is one fact");
        }
    }

    @Test
    void secretsAreOnlyEverSaidToBeSet() {
        final Properties properties = new Properties();
        properties.setProperty("synaxis.app.port", "25601");
        properties.setProperty("synaxis.app.db-password", "hunter2");
        properties.setProperty("user.home", "/home/somebody");

        final String text = CrashReports.describe(List.of("ServerConfig[dispatcherThreads=1]"), properties,
                Map.of("SYNAXIS_MANAGEMENT_TOKEN", "abc123", "PATH", "/usr/bin"),
                List.of("-Xmx4G", "-Dsynaxis.app.api-token=xyz789", "-Dsynaxis.app.shard-id=r01-c1"));

        assertEquals("""
                ServerConfig[dispatcherThreads=1]
                synaxis.app.db-password=<set>
                synaxis.app.port=25601
                env SYNAXIS_MANAGEMENT_TOKEN=<set>
                jvm -Xmx4G
                jvm -Dsynaxis.app.api-token=<set>
                jvm -Dsynaxis.app.shard-id=r01-c1
                """, text);
        assertFalse(text.contains("hunter2") || text.contains("abc123") || text.contains("xyz789"), text);
    }
}
