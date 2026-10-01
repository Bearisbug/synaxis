package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * The local analytics ledger of {@code docs/DESIGN-M2.md} §20: one JSON object per line in
 * {@code logs/events-<shard_id>-YYYYMMDD.jsonl}, written as {@code docs/DESIGN-PROD.md}
 * {@code API-PROD-014} and {@code ADR-PROD-005} say.
 * <p>
 * {@link #record} builds the whole line on the calling thread — the {@code ts} is taken there, as
 * before — and offers it to a bounded queue of {@value #QUEUE_CAPACITY} lines. It never blocks: a
 * full queue drops the new line and counts it. One writer thread, {@value #WRITER_THREAD}, owns the
 * file and writes once {@value #FLUSH_INTERVAL_MS} ms have passed since its last write or its buffer
 * holds {@value #BUFFER_BYTES} bytes, without an {@code fsync} per write; {@link #close} drains the
 * queue within {@value #CLOSE_BUDGET_MS} ms and forces the file once. A crash therefore loses at most
 * the last second, a normal stop loses nothing.
 * <p>
 * Connections are still served while the shutdown tasks run, so {@link #record} can race
 * {@link #close}. Every {@code record} counts itself in {@link #recording} before it looks at
 * {@link #sealed}; the writer seals the queue, waits for that count to reach zero and only then
 * drains for the last time. A line is thus either in the file or refused by a {@code record} that
 * saw the seal; those refused lines have no file left to be reported in and are counted in the log.
 * <p>
 * Dropped lines are reported in the file itself: the next write starts with one
 * {@code {"event":"ledger_dropped","reason":…,"count":N,"since":…}} line per reason before the lines
 * it carries. The reasons are {@code queue_full} and {@code daily_cap}: a day's file stops taking
 * events at {@code synaxis.app.ledger-max-bytes-per-day} (1 GiB by default), after which only those
 * summary lines are written until the next day.
 * <p>
 * A line goes to the file of the day its own {@code ts} falls on, in the zone of the clock. A line
 * whose day is earlier than the open file's — two threads racing past midnight, a few microseconds
 * apart — goes into the open file rather than reopening a file that is being compressed. Once the
 * writer moves on to a new day, and once when it starts, every earlier file of this shard is
 * gzipped to {@code .jsonl.gz} and every file older than {@value #RETENTION_DAYS} days is deleted.
 * <p>
 * The lines say who played when and from where, so whatever the umask the files are created 0600,
 * compressed ones too, and the directory, when the ledger is the one creating it, 0700.
 * <p>
 * The rate is not the host's to choose: {@code PluginAnalytics} hands {@link #record} to every
 * plugin and every plugin records from its own inbound path, so every failure reports through a
 * {@link LogBudget} rather than once per event ({@code docs/DESIGN.md} §15).
 */
final class EventLedger {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventLedger.class);

    static final int QUEUE_CAPACITY = 65_536;
    static final int BUFFER_BYTES = 64 * 1024;
    static final long FLUSH_INTERVAL_MS = 1_000;
    static final long CLOSE_BUDGET_MS = 2_000;
    static final int RETENTION_DAYS = 30;
    static final long DEFAULT_MAX_BYTES_PER_DAY = 1L << 30;
    static final String WRITER_THREAD = "Syn-Ledger-Writer";

    /** How long the writer waits for a line before it looks at the clock and the close flag again. */
    private static final long POLL_MS = 50;

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private final Path directory;
    private final String shardId;
    private final long maxBytesPerDay;
    private final Clock clock;
    private final Pattern fileName;

    private final BlockingQueue<Line> queue;
    private final Drops queueFull = new Drops("queue_full");
    private final Drops dailyCap = new Drops("daily_cap");

    /** The failures of the file itself, one budget for the whole ledger: it is one fact, whoever writes. */
    private final LogBudget failures = new LogBudget(LOGGER, "ledger write failures");
    private final LogBudget dropReports = new LogBudget(LOGGER, "ledger drop reports");

    private volatile boolean closing;
    /** Set by the writer once it stops taking lines, before its last drain. */
    private volatile boolean sealed;
    /** The {@link #record} calls between their look at {@link #sealed} and their offer. */
    private final AtomicInteger recording = new AtomicInteger();
    private final AtomicLong refusedAfterSeal = new AtomicLong();
    /** The lines dropped for a full queue or the daily cap since the process started. */
    private final AtomicLong droppedTotal = new AtomicLong();
    private @Nullable Thread writer;

    // Owned by the writer thread
    private final Buffer buffer = new Buffer(BUFFER_BYTES);
    private @Nullable FileChannel channel;
    private long channelDay = Long.MIN_VALUE;
    private long bytesToday;

    /**
     * @param directory      where the files go, created on the first write
     * @param shardId        the shard id, part of every file name
     * @param maxBytesPerDay the size at which a day's file stops taking events
     */
    EventLedger(Path directory, String shardId, long maxBytesPerDay) {
        this(directory, shardId, maxBytesPerDay, Clock.system(ZoneId.systemDefault()), QUEUE_CAPACITY);
    }

    EventLedger(Path directory, String shardId, long maxBytesPerDay, Clock clock, int queueCapacity) {
        this.directory = directory;
        this.shardId = shardId;
        this.maxBytesPerDay = maxBytesPerDay;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.fileName = Pattern.compile("events-" + Pattern.quote(shardId) + "-(\\d{8})\\.jsonl(\\.gz)?");
    }

    /**
     * Starts the writer thread and the housekeeping of the days before today. Lines recorded before
     * this wait in the queue.
     */
    synchronized void start() {
        if (writer != null) return;
        housekeepInBackground(LocalDate.now(clock));
        writer = Thread.ofVirtual().name(WRITER_THREAD).start(this::run);
    }

    /**
     * Records one event. Never blocks and never throws.
     *
     * @param event      the event name, without the {@code EVT:} prefix the design document uses
     * @param attributes the attributes of that event, written in iteration order
     */
    void record(String event, Map<String, ?> attributes) {
        recording.incrementAndGet();
        try {
            if (sealed) {
                dropReports.warn("The event ledger is closed and dropped a line: dropped_after_close={}",
                        refusedAfterSeal.incrementAndGet());
                return;
            }
            offer(event, attributes);
        } finally {
            recording.decrementAndGet();
        }
    }

    private void offer(String event, Map<String, ?> attributes) {
        final Instant now = clock.instant();
        final StringBuilder line = new StringBuilder(128);
        line.append("{\"ts\":\"").append(now).append("\",\"event\":\"").append(event).append('"');
        for (Map.Entry<String, ?> attribute : attributes.entrySet()) {
            line.append(",\"").append(attribute.getKey()).append("\":");
            appendValue(line, attribute.getValue());
        }
        line.append('}').append('\n');
        final Line entry = new Line(LocalDate.ofInstant(now, clock.getZone()).toEpochDay(),
                line.toString().getBytes(StandardCharsets.UTF_8));
        if (!queue.offer(entry)) queueFull.count(now);
    }

    /**
     * Returns the lines dropped because the queue was full or the day's file had reached its cap,
     * since the process started ({@code synaxis_ledger_dropped_total}); the lines refused
     * after close are not among them.
     */
    long droppedTotal() {
        return droppedTotal.get();
    }

    /**
     * Drains what is queued, forces the file and stops the writer, waiting at most
     * {@value #CLOSE_BUDGET_MS} ms. Registered as a shutdown task after the plugins are disabled, so
     * the events they record in {@code onDisable} are on disk.
     */
    void close() {
        final Thread thread;
        synchronized (this) {
            closing = true;
            thread = writer;
        }
        if (thread == null) return;
        try {
            if (!thread.join(Duration.ofMillis(CLOSE_BUDGET_MS)))
                failures.warn("The event ledger did not drain within {} ms: lines_left={}", CLOSE_BUDGET_MS, queue.size());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long lastWrite = System.nanoTime();
        while (true) {
            final boolean draining = closing;
            final long sinceWriteMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastWrite);
            final Line line;
            try {
                line = draining ? queue.poll()
                        : queue.poll(Math.max(1, Math.min(POLL_MS, FLUSH_INTERVAL_MS - sinceWriteMs)), TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                continue;
            }
            if (line != null) accept(line);
            final boolean due = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastWrite) >= FLUSH_INTERVAL_MS;
            if (buffer.size >= BUFFER_BYTES || due) {
                writeOut();
                lastWrite = System.nanoTime();
            }
            if (draining && line == null) {
                drainSealed();
                return;
            }
        }
    }

    /** Refuses new lines, waits for the {@link #record} calls already past the seal, writes the rest. */
    private void drainSealed() {
        sealed = true;
        while (recording.get() != 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        for (Line line = queue.poll(); line != null; line = queue.poll()) {
            accept(line);
            if (buffer.size >= BUFFER_BYTES) writeOut();
        }
        writeOut();
        closeChannel(true);
    }

    private void accept(Line line) {
        if (line.day > channelDay) switchDay(line.day);
        if (bytesToday + buffer.size + line.bytes.length > maxBytesPerDay) {
            dailyCap.count(clock.instant());
            return;
        }
        buffer.append(line.bytes);
    }

    private void switchDay(long day) {
        final boolean rollover = channelDay != Long.MIN_VALUE;
        writeOut();
        closeChannel(false);
        openDay(day);
        if (rollover) housekeepInBackground(LocalDate.ofEpochDay(day));
    }

    private void openDay(long day) {
        channelDay = day;
        final Path file = file(LocalDate.ofEpochDay(day));
        try {
            Files.createDirectories(directory, OWNER_ONLY_DIRECTORY);
            channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND), OWNER_ONLY);
            bytesToday = channel.size();
        } catch (IOException e) {
            failures.warn(e, "Could not open the event ledger: file={}", file);
            bytesToday = 0;
        }
    }

    /** Writes the drop summaries, then the buffered lines. */
    private void writeOut() {
        final Buffer summaries = new Buffer(256);
        final Instant now = clock.instant();
        queueFull.report(summaries, now);
        dailyCap.report(summaries, now);
        if (summaries.size == 0 && buffer.size == 0) return;
        final int lines = buffer.lines;
        if (channel == null)
            openDay(channelDay != Long.MIN_VALUE ? channelDay : LocalDate.ofInstant(now, clock.getZone()).toEpochDay());
        final FileChannel target = channel;
        try {
            if (target == null) {
                failures.warn("Lost {} event ledger lines: the file could not be opened", lines);
                return;
            }
            final ByteBuffer[] bytes = {ByteBuffer.wrap(summaries.bytes, 0, summaries.size),
                    ByteBuffer.wrap(buffer.bytes, 0, buffer.size)};
            while (bytes[0].hasRemaining() || bytes[1].hasRemaining()) target.write(bytes);
            bytesToday += summaries.size + buffer.size;
        } catch (IOException e) {
            failures.warn(e, "Lost {} event ledger lines: the write failed", lines);
            closeChannel(false);
        } finally {
            buffer.clear();
        }
    }

    private void closeChannel(boolean force) {
        final FileChannel open = channel;
        if (open == null) return;
        channel = null;
        try {
            if (force) open.force(true);
            open.close();
        } catch (IOException e) {
            failures.warn(e, "Could not close the event ledger");
        }
    }

    private Path file(LocalDate day) {
        return directory.resolve("events-" + shardId + "-" + FILE_DATE.format(day) + ".jsonl");
    }

    private void housekeepInBackground(LocalDate today) {
        Thread.ofVirtual().name(WRITER_THREAD + "-Housekeeping").start(() -> housekeep(today));
    }

    /**
     * Compresses every file of this shard from before {@code today} and deletes every file older than
     * the retention. A compressed file is written next to the original and moved into place before
     * the original is deleted, so a process killed halfway leaves the plain file to be compressed on
     * the next start.
     *
     * @param today the day being written, the one file left alone
     */
    void housekeep(LocalDate today) {
        if (!Files.isDirectory(directory)) return;
        final LocalDate oldestKept = today.minusDays(RETENTION_DAYS);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                final Matcher match = fileName.matcher(path.getFileName().toString());
                if (!match.matches()) continue;
                final LocalDate day = LocalDate.parse(match.group(1), FILE_DATE);
                final boolean compressed = match.group(2) != null;
                if (day.isBefore(oldestKept)) Files.deleteIfExists(path);
                else if (!compressed && day.isBefore(today)) compress(path);
            }
        } catch (IOException | RuntimeException e) {
            failures.warn(e, "Could not compress or prune the event ledger: directory={}", directory);
        }
    }

    private static void compress(Path plain) throws IOException {
        final Path gzip = plain.resolveSibling(plain.getFileName() + ".gz");
        final Path partial = plain.resolveSibling(plain.getFileName() + ".gz.partial");
        // One a killed process left behind would keep the mode it was created with
        Files.deleteIfExists(partial);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(Files.createFile(partial, OWNER_ONLY)))) {
            Files.copy(plain, out);
        }
        Files.move(partial, gzip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.delete(plain);
    }

    private static void appendValue(StringBuilder line, @Nullable Object value) {
        if (value instanceof Number number) {
            line.append(number);
            return;
        }
        line.append('"');
        final String text = String.valueOf(value);
        for (int i = 0; i < text.length(); i++) {
            final char character = text.charAt(i);
            switch (character) {
                case '"' -> line.append("\\\"");
                case '\\' -> line.append("\\\\");
                case '\n' -> line.append("\\n");
                case '\r' -> line.append("\\r");
                case '\t' -> line.append("\\t");
                default -> {
                    if (character < ' ') line.append("\\u%04x".formatted((int) character));
                    else line.append(character);
                }
            }
        }
        line.append('"');
    }

    /**
     * One line, already encoded.
     *
     * @param day   the epoch day of its {@code ts}, which picks the file
     * @param bytes the line with its line feed
     */
    private record Line(long day, byte[] bytes) {
    }

    /** The lines dropped for one reason since the last summary line. */
    private final class Drops {
        private final String reason;
        private final AtomicLong count = new AtomicLong();
        private final AtomicReference<Instant> since = new AtomicReference<>();

        Drops(String reason) {
            this.reason = reason;
        }

        void count(Instant now) {
            since.compareAndSet(null, now);
            count.incrementAndGet();
            droppedTotal.incrementAndGet();
        }

        void report(Buffer out, Instant now) {
            final long dropped = count.getAndSet(0);
            if (dropped == 0) return;
            final Instant first = since.getAndSet(null);
            out.append(("{\"ts\":\"" + now + "\",\"event\":\"ledger_dropped\",\"reason\":\"" + reason
                    + "\",\"count\":" + dropped + ",\"since\":\"" + (first == null ? now : first) + "\"}\n")
                    .getBytes(StandardCharsets.UTF_8));
            dropReports.warn("The event ledger dropped {} lines: reason={}", dropped, reason);
        }
    }

    /** A growable byte buffer that also counts its lines. */
    private static final class Buffer {
        private byte[] bytes;
        private int size;
        private int lines;

        Buffer(int capacity) {
            this.bytes = new byte[capacity];
        }

        void append(byte[] line) {
            ensure(line.length);
            System.arraycopy(line, 0, bytes, size, line.length);
            size += line.length;
            lines++;
        }

        private void ensure(int extra) {
            if (size + extra > bytes.length) bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + extra));
        }

        void clear() {
            size = 0;
            lines = 0;
        }
    }
}
