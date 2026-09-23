package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The buffered ledger of {@code docs/DESIGN-PROD.md} {@code API-PROD-014} ({@code REQ-PROD-017}).
 * <p>
 * The ledger is handed to every plugin as {@code PluginAnalytics}, and {@code docs/DESIGN-M2.md}
 * §20 asks a plugin for one event per inbound order packet, so the rate of {@code record} is the
 * client's to pick: it must never block the tick thread, and a file it cannot write to must not
 * cost a line per event.
 */
class EventLedgerTest {

    /** Big enough that a line per event is unmistakable, small enough to stay a unit of work. */
    private static final int FLOOD = 10_000;

    private static final String SHARD = "r01-c1";
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOON = Instant.parse("2026-09-23T12:00:00Z");

    /** A clock the test moves across midnight. */
    private static class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static EventLedger ledger(Path directory, long maxBytesPerDay, Clock clock) {
        return new EventLedger(directory, SHARD, maxBytesPerDay, clock, EventLedger.QUEUE_CAPACITY);
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static Path dayFile(Path directory, String day) {
        return directory.resolve("events-" + SHARD + "-" + day + ".jsonl");
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout, String message) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError(message);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    @Test
    void aFloodOfEventsIntoAnUnwritableLedgerWritesOneLine(@TempDir Path directory) throws IOException {
        // A regular file where the ledger wants its directory, so every write fails the same way
        final Path blocked = directory.resolve("logs");
        Files.writeString(blocked, "not a directory");
        final EventLedger ledger = new EventLedger(blocked, SHARD, EventLedger.DEFAULT_MAX_BYTES_PER_DAY);

        try (LogCapture logs = LogCapture.of(EventLedger.class)) {
            ledger.start();
            for (int i = 0; i < FLOOD; i++) {
                ledger.record("unit_order_issued", Map.of("order_seq", i));
            }
            ledger.close();

            final List<ILoggingEvent> warnings = logs.eventsAt(Level.WARN);
            assertEquals(1, warnings.size(),
                    "a plugin records one of these per inbound order packet, so the client sets the rate");
            assertTrue(warnings.getFirst().getFormattedMessage().contains("since the last report"),
                    warnings.getFirst().getFormattedMessage());
            assertNotNull(warnings.getFirst().getThrowableProxy(),
                    "the one report that is written has to say why the write failed");
        }
    }

    // §19: "台账缓冲写入且满即丢新". The writer not running yet stands in for a blocked writer.
    @Test
    void aFullQueueDropsTheNewLinesWithoutBlockingAndSaysSoFirst(@TempDir Path directory) throws IOException {
        final EventLedger ledger = ledger(directory, EventLedger.DEFAULT_MAX_BYTES_PER_DAY, new MovableClock(NOON));
        final int produced = 70_000;

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < produced; i++) ledger.record("unit_order_issued", Map.of("order_seq", i));
        }, "record must never wait for the writer");
        ledger.start();
        ledger.close();

        final List<String> lines = lines(dayFile(directory, "20260923"));
        assertEquals(1 + EventLedger.QUEUE_CAPACITY, lines.size());
        final String summary = lines.getFirst();
        assertTrue(summary.contains("\"event\":\"ledger_dropped\""), summary);
        assertTrue(summary.contains("\"reason\":\"queue_full\""), summary);
        assertTrue(summary.contains("\"count\":" + (produced - EventLedger.QUEUE_CAPACITY)), summary);
        assertTrue(summary.contains("\"since\":\"" + NOON + "\""), summary);
        // The oldest lines are the ones kept, in the order they were recorded
        for (int i = 0; i < EventLedger.QUEUE_CAPACITY; i++) {
            final String line = lines.get(1 + i);
            assertTrue(line.endsWith("\"order_seq\":" + i + "}"), line);
        }
    }

    @Test
    void aNormalStopLosesNothingFromAnyThread(@TempDir Path directory) throws Exception {
        final EventLedger ledger = ledger(directory, EventLedger.DEFAULT_MAX_BYTES_PER_DAY, new MovableClock(NOON));
        ledger.start();
        final List<Thread> producers = new ArrayList<>();
        for (int thread = 0; thread < 4; thread++) {
            final int id = thread;
            producers.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < FLOOD; i++) ledger.record("unit_order_issued", Map.of("thread", id, "order_seq", i));
            }));
        }
        for (Thread producer : producers) producer.join();
        ledger.close();

        final List<String> lines = lines(dayFile(directory, "20260923"));
        assertEquals(4 * FLOOD, lines.size());
        assertFalse(lines.stream().anyMatch(line -> line.contains("ledger_dropped")), "nothing was dropped");
    }

    // A connection is still served while the shutdown task runs: its record can be past the look at
    // the close and not yet in the queue when the writer finds the queue empty
    @Test
    void aRecordRacingTheCloseIsWrittenOrRefusedNeverLeftInTheQueue(@TempDir Path directory) throws Exception {
        final CountDownLatch inRecord = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Clock clock = new MovableClock(NOON) {
            @Override
            public Instant instant() {
                if (Thread.currentThread().getName().equals("racing-recorder")) {
                    inRecord.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.instant();
            }
        };
        final EventLedger ledger = ledger(directory, EventLedger.DEFAULT_MAX_BYTES_PER_DAY, clock);
        ledger.start();
        final Thread recorder = Thread.ofPlatform().name("racing-recorder")
                .start(() -> ledger.record("client_verified", Map.of("connection_id", "racing")));
        assertTrue(inRecord.await(5, TimeUnit.SECONDS));
        final Thread closer = Thread.ofPlatform().start(ledger::close);
        // Long enough for the writer to see the close and find the queue empty
        TimeUnit.MILLISECONDS.sleep(300);
        release.countDown();
        recorder.join();
        closer.join();

        try (LogCapture logs = LogCapture.of(EventLedger.class)) {
            ledger.record("client_verified", Map.of("connection_id", "late"));
            assertEquals(1, logs.eventsAt(Level.WARN).size(), "a line after the close is counted in the log");
        }
        final List<String> lines = readQuietly(dayFile(directory, "20260923"));
        assertEquals(1, lines.size(), "the racing line is in the file: " + lines);
        assertTrue(lines.getFirst().contains("\"connection_id\":\"racing\""), lines.getFirst());
    }

    // One write per interval or per full buffer, not one per event
    @Test
    void aFewLinesWaitForTheIntervalAndAFullBufferDoesNot(@TempDir Path directory) throws Exception {
        final EventLedger ledger = ledger(directory, EventLedger.DEFAULT_MAX_BYTES_PER_DAY, new MovableClock(NOON));
        final Path file = dayFile(directory, "20260923");
        ledger.start();
        try {
            final long start = System.nanoTime();
            ledger.record("session_entered_configuration", Map.of("connection_id", "a"));
            TimeUnit.MILLISECONDS.sleep(200);
            assertFalse(Files.exists(file) && Files.size(file) > 0, "one line is not a write of its own");
            awaitTrue(() -> readQuietly(file).size() == 1, Duration.ofSeconds(3), "the line is written within the interval");
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(900),
                    "the first write waited for the interval");

            // A burst bigger than the buffer is written without waiting for the next interval
            final String filler = "x".repeat(200);
            final long burst = System.nanoTime();
            for (int i = 0; i < 1_000; i++) ledger.record("filler", Map.of("i", i, "text", filler));
            awaitTrue(() -> readQuietly(file).size() > 1, Duration.ofSeconds(3), "a full buffer is written");
            assertTrue(System.nanoTime() - burst < TimeUnit.MILLISECONDS.toNanos(900),
                    "a full buffer does not wait for the interval");
        } finally {
            ledger.close();
        }
    }

    // §19: "台账文件按分片与日期命名并受每日上限约束"
    @Test
    void aDayStopsAtItsCapAndIsCompressedOnceTheNextDayStarts(@TempDir Path directory) throws Exception {
        final MovableClock clock = new MovableClock(NOON);
        final long cap = 1024 * 1024;
        final EventLedger ledger = ledger(directory, cap, clock);
        ledger.start();
        final String filler = "x".repeat(1_000);
        int recorded = 0;
        for (long bytes = 0; bytes < 2 * cap; bytes += filler.length()) {
            ledger.record("filler", Map.of("i", recorded++, "text", filler));
        }
        final Path day = dayFile(directory, "20260923");
        final int total = recorded;
        // Every line ends up either in the file or in the count of a summary line
        awaitTrue(() -> written(readQuietly(day)) + dropped(readQuietly(day)) == total, Duration.ofSeconds(5),
                "every capped line is reported");
        final List<String> capped = readQuietly(day);
        assertTrue(dropped(capped) > 0, "2 MB into a 1 MB day drops lines");
        assertTrue(Files.size(day) <= cap + 1024, "the day holds its cap, plus the summary lines: " + Files.size(day));

        clock.now = NOON.plus(Duration.ofDays(1));
        ledger.record("session_entered_configuration", Map.of("connection_id", "next-day"));
        ledger.close();

        final Path nextDay = dayFile(directory, "20260924");
        assertEquals(1, lines(nextDay).size());
        final Path compressed = directory.resolve(day.getFileName() + ".gz");
        awaitTrue(() -> Files.exists(compressed) && !Files.exists(day), Duration.ofSeconds(5),
                "the day before is compressed once the next one starts");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(compressed))) {
            assertEquals(String.join("\n", capped) + "\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void housekeepingKeepsThirtyDaysOfThisShardOnly(@TempDir Path directory) throws IOException {
        final LocalDate today = LocalDate.of(2026, 9, 23);
        final EventLedger ledger = ledger(directory, EventLedger.DEFAULT_MAX_BYTES_PER_DAY, new MovableClock(NOON));
        Files.writeString(dayFile(directory, "20260822"), "{}\n");               // 32 days old, plain
        Files.writeString(directory.resolve("events-" + SHARD + "-20260823.jsonl.gz"), "gz"); // 31 days old
        Files.writeString(dayFile(directory, "20260824"), "{}\n");               // 30 days old, kept
        Files.writeString(dayFile(directory, "20260922"), "{}\n");               // yesterday
        Files.writeString(dayFile(directory, "20260923"), "{}\n");               // today, being written
        Files.writeString(directory.resolve("events-other-20260801.jsonl"), "{}\n");
        Files.writeString(directory.resolve("events-20260801.jsonl"), "{}\n");  // the name before shard ids

        ledger.housekeep(today);

        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(
                    "events-20260801.jsonl",
                    "events-other-20260801.jsonl",
                    "events-" + SHARD + "-20260824.jsonl.gz",
                    "events-" + SHARD + "-20260922.jsonl.gz",
                    "events-" + SHARD + "-20260923.jsonl"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static long written(List<String> lines) {
        return lines.stream().filter(line -> line.contains("\"event\":\"filler\"")).count();
    }

    private static long dropped(List<String> lines) {
        return lines.stream().filter(line -> line.contains("\"reason\":\"daily_cap\"")).mapToLong(summary -> {
            final int start = summary.indexOf("\"count\":") + "\"count\":".length();
            return Long.parseLong(summary.substring(start, summary.indexOf(',', start)));
        }).sum();
    }

    private static List<String> readQuietly(Path file) {
        try {
            return Files.exists(file) ? lines(file) : List.of();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
