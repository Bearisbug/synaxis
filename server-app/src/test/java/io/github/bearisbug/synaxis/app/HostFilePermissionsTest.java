package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The files the host writes about players and about itself are its own, whatever the umask
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-013}, {@code API-PROD-014}, §23): the event ledger,
 * compressed days included, and the crash reports are created 0600, and the directories the host
 * creates for them 0700.
 */
class HostFilePermissionsTest {

    @TempDir
    Path directory;

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void theLedgerAndTheDirectoryItCreatesAreOwnerOnly() throws IOException {
        final Path logs = directory.resolve("logs");
        final EventLedger ledger = new EventLedger(logs, "r01-c1", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        ledger.record("player_joined", Map.of("player_uuid", "00000000-0000-0000-0000-000000000001"));
        ledger.close();

        assertEquals("rwx------", permissions(logs));
        try (Stream<Path> files = Files.list(logs)) {
            final List<Path> written = files.toList();
            assertEquals(1, written.size(), written.toString());
            assertEquals("rw-------", permissions(written.getFirst()), written.getFirst().toString());
        }
    }

    @Test
    void aCompressedDayIsOwnerOnly() throws IOException {
        // Written by a version that left the mode to the umask
        Files.writeString(directory.resolve("events-r01-c1-20260922.jsonl"), "{\"event\":\"player_joined\"}\n");

        new EventLedger(directory, "r01-c1", EventLedger.DEFAULT_MAX_BYTES_PER_DAY).housekeep(LocalDate.of(2026, 9, 23));

        assertEquals("rw-------", permissions(directory.resolve("events-r01-c1-20260922.jsonl.gz")));
    }

    @Test
    void aCrashReportAndTheDirectoryItCreatesAreOwnerOnly() throws IOException {
        final Path reports = directory.resolve("crash-reports");

        final Path file = new CrashReports(reports, "r01-c1", Clock.systemUTC(), () -> "", List::of)
                .write("fatal error: test", null);

        assertNotNull(file);
        assertEquals("rwx------", permissions(reports));
        assertEquals("rw-------", permissions(file));
    }
}
