package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The final flush and the write right ({@code docs/DESIGN-M3.md} API-M3-007 shutdown, §12.5, §19
 * {@code REQ-M3-011} overlay store unit tests): exactly one of the tick end write phase and the
 * shutdown thread writes it, and nothing is written after it.
 */
public class OverlayFinalFlushTest {

    private static final Duration TWO_SECONDS = Duration.ofSeconds(2);

    @TempDir
    Path root;

    private final OverlayFixture.Clock clock = new OverlayFixture.Clock();
    private final OverlayFixture.CountingDisk disk = new OverlayFixture.CountingDisk();
    private final SynxManifest manifest = OverlayFixture.manifest(776);

    private OverlayStore open(int encodeThreads) throws Exception {
        return open(root, encodeThreads, disk);
    }

    private OverlayStore open(Path overlayRoot, int encodeThreads, OverlayFixture.CountingDisk disk) throws Exception {
        return OverlayStore.open(overlayRoot, manifest, OverlayFixture.settings(), new OverlayOptions(16_384, 10),
                OverlayFixture.tuning(encodeThreads, clock, disk));
    }

    private static List<FakeChunk> dirty(OverlayStore store, int count) {
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, count);
        chunks.forEach(FakeChunk::change);
        return chunks;
    }

    private static String flushLine(LogCapture log, Level level) {
        final List<String> lines = log.messagesAt(level).stream().filter(line -> line.startsWith("Overlay final flush:")).toList();
        assertEquals(1, lines.size(), () -> "one final flush line at " + level + ": " + log.messagesAt(level));
        return lines.getFirst();
    }

    private void assertWritten(List<FakeChunk> chunks) throws Exception {
        final OverlayStore reopened = open(1);
        for (FakeChunk chunk : chunks) {
            final OverlayHit hit = reopened.find(chunk.chunkX(), chunk.chunkZ());
            assertNotNull(hit);
            assertEquals(chunk.generation(), FakeChunk.generationOf(hit, chunk.chunkX(), chunk.chunkZ()));
        }
    }

    @Test
    public void aDeadTickLeavesTheFinalFlushToTheShutdownThread() throws Exception {
        final OverlayStore store = open(2);
        final List<FakeChunk> chunks = dirty(store, 30);
        final String shutdownThread = Thread.currentThread().getName();

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            final CompletableFuture<?> done = store.finalFlush(Duration.ofSeconds(15), false);
            assertTrue(done.isDone(), "done on the calling thread");
            final String line = flushLine(log, Level.INFO);
            assertTrue(line.contains("chunks=30") && line.contains("left=0") && line.endsWith("by=shutdown"), line);
        }
        assertEquals(1, disk.forces.get());
        assertEquals(Set.of(shutdownThread), Set.copyOf(disk.forceThreads));
        assertEquals(Set.of(shutdownThread), Set.copyOf(disk.writeThreads), "appended on the shutdown thread");
        chunks.forEach(chunk -> assertFalse(chunk.dirty()));
        store.tickEnd();
        assertEquals(1, disk.forces.get(), "closed: the tick writes nothing any more");
        assertWritten(chunks);
    }

    @Test
    public void aLiveFinalFlushIsDoneByTheNextTickEnd() throws Exception {
        final OverlayStore store = open(2);
        final List<FakeChunk> chunks = dirty(store, 30);

        final CompletableFuture<?> done = store.finalFlush(TWO_SECONDS, true);
        assertFalse(done.isDone(), "handed to the tick, not done on the calling thread");
        assertEquals(0, disk.writes.get());
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store.tickEnd();
            assertTrue(flushLine(log, Level.INFO).endsWith("left=0 by=tick"), log.messagesAt(Level.INFO)::toString);
        }
        assertTrue(done.isDone());
        final int writes = disk.writes.get();
        store.tickEnd();
        assertEquals(writes, disk.writes.get());
        assertEquals(1, disk.forces.get());
        assertWritten(chunks);
    }

    @Test
    public void aRequestDuringAWritePhaseIsServedByTheNextOne() throws Exception {
        final OverlayStore store = open(1);
        final FakeChunk due = new FakeChunk(store, -1, -1, 0);
        due.change();
        clock.advance(Duration.ofSeconds(10));
        final List<FakeChunk> chunks = dirty(store, 5);
        final AtomicReference<CompletableFuture<?>> done = new AtomicReference<>();
        disk.onForce = () -> done.compareAndSet(null, store.finalFlush(TWO_SECONDS, true));

        store.tickEnd();

        assertNotNull(done.get());
        assertFalse(done.get().isDone(), "the phase already under way writes its own batch only");
        assertFalse(due.dirty());
        chunks.forEach(chunk -> assertTrue(chunk.dirty()));
        store.tickEnd();
        assertTrue(done.get().isDone());
        chunks.forEach(chunk -> assertFalse(chunk.dirty()));
        assertEquals(2, disk.forces.get());
    }

    @Test
    public void anEncodingAWritePhaseLeftBehindIsDoneAgainOnceTheChunkChanged() throws Exception {
        final OverlayStore store = openWithTheShardPhase();
        final FakeChunk chunk = new FakeChunk(store, 2, 2, 0);
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        chunk.inEncoding = () -> {
            entered.countDown();
            await(release);
        };
        store.tickEnd(); // gives up on the encoding after 20 ms; it goes on, at generation 1
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        chunk.inEncoding = null;
        final Thread changing = Thread.ofPlatform().start(chunk::change); // waits for the encoding's write-lock
        release.countDown();
        changing.join();
        assertEquals(2, chunk.generation());

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            var _ = store.finalFlush(TWO_SECONDS, false);
            final String line = flushLine(log, Level.INFO);
            assertTrue(line.contains("chunks=1 ") && line.contains("left=0"), line);
        }
        assertFalse(chunk.dirty());
        assertEquals(2, chunk.encodes.get(), "encoded again, at generation 2");
        assertWritten(List.of(chunk));
    }

    @Test
    public void aChunkAWritePhaseCouldNotEncodeIsEncodedAgain() throws Exception {
        final OverlayStore store = openWithTheShardPhase();
        final FakeChunk slow = new FakeChunk(store, 0, 0, 0);
        final FakeChunk refused = new FakeChunk(store, 1, 0, 0);
        slow.change();
        refused.change();
        clock.advance(Duration.ofSeconds(10));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        slow.inEncoding = () -> {
            entered.countDown();
            await(release);
        };
        refused.nextEncodings.add(FakeChunk.Encoding.NULL); // its write-lock not had in time, say
        store.tickEnd(); // both left in flight: the first holds up the one encode thread
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        slow.inEncoding = null;
        release.countDown();

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            var _ = store.finalFlush(TWO_SECONDS, false);
            final String line = flushLine(log, Level.INFO);
            assertTrue(line.contains("chunks=2 ") && line.contains("left=0"), line);
        }
        assertEquals(1, slow.encodes.get(), "its encoding is still at its generation: taken as it is");
        assertEquals(2, refused.encodes.get());
        assertWritten(List.of(slow, refused));
    }

    @Test
    public void aSecondCallTakesTheEarlierOfTheTwoDeadlines() throws Exception {
        assertTheEarlierDeadline(root.resolve("live-earlier"), Duration.ofMillis(300), Duration.ofSeconds(30));
        assertTheEarlierDeadline(root.resolve("dead-earlier"), Duration.ofSeconds(2), Duration.ofMillis(300));
    }

    /** 60 chunks of 20 ms on one encode thread: 1.2 s of encoding, more than the 300 ms either order leaves. */
    private void assertTheEarlierDeadline(Path overlayRoot, Duration live, Duration dead) throws Exception {
        final OverlayStore store = open(overlayRoot, 1, new OverlayFixture.CountingDisk());
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 60);
        for (FakeChunk chunk : chunks) {
            chunk.encodeNanos = TimeUnit.MILLISECONDS.toNanos(20);
            chunk.change();
        }
        final long start = System.nanoTime();
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            var _ = store.finalFlush(live, true);
            var _ = store.finalFlush(dead, false);
            final long took = System.nanoTime() - start;
            assertTrue(took >= TimeUnit.MILLISECONDS.toNanos(300) && took < TimeUnit.MILLISECONDS.toNanos(1_000),
                    () -> live + " then " + dead + ": the final flush took " + took / 1_000_000 + " ms");
            final String line = flushLine(log, Level.ERROR);
            assertTrue(line.endsWith("by=shutdown") && !line.contains("left=0"), line);
        }
        final long late = start + TimeUnit.MILLISECONDS.toNanos(320);
        assertTrue(chunks.stream().flatMap(chunk -> chunk.encodeStarts.stream()).filter(at -> at - late > 0).count() <= 1,
                () -> live + " then " + dead + ": encodings started after the earlier deadline");
    }

    @Test
    public void aLiveCallThenADeadOneWriteTheFinalFlushOnce() throws Exception {
        final OverlayStore store = open(1);
        final List<FakeChunk> chunks = dirty(store, 5);

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            final CompletableFuture<?> live = store.finalFlush(Duration.ofSeconds(30), true);
            final CompletableFuture<?> dead = store.finalFlush(Duration.ofSeconds(15), false);
            assertTrue(dead.isDone());
            assertTrue(live.isDone(), "the future of the first call stands for both");
            assertTrue(flushLine(log, Level.INFO).contains("chunks=5"));
            assertTrue(flushLine(log, Level.INFO).endsWith("by=shutdown"));
        }
        assertEquals(1, disk.forces.get());
        assertEquals(5, store.stats().flushAppended());
        chunks.forEach(chunk -> assertEquals(1, chunk.written.size()));
    }

    @Test
    public void aFinalFlushTheTickStartedIsWaitedForAndNotRepeated() throws Exception {
        final OverlayStore store = open(2);
        final List<FakeChunk> chunks = dirty(store, 10);
        final CompletableFuture<?> requested = store.finalFlush(TWO_SECONDS, true);
        final CountDownLatch inFsync = new CountDownLatch(1);
        disk.onForce = () -> {
            inFsync.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            final Thread tick = Thread.ofPlatform().name("tick-end").start(store::tickEnd);
            assertTrue(inFsync.await(5, TimeUnit.SECONDS));
            final CompletableFuture<?> second = store.finalFlush(TWO_SECONDS, false);
            assertTrue(second.isDone(), "the shutdown thread returns once the tick's final flush is done");
            assertTrue(requested.isDone());
            tick.join();
            assertTrue(flushLine(log, Level.INFO).endsWith("left=0 by=tick"));
        }
        assertEquals(1, disk.forces.get());
        assertEquals(Set.of("tick-end"), Set.copyOf(disk.forceThreads));
        assertEquals(Set.of("tick-end"), Set.copyOf(disk.writeThreads), "the shutdown thread wrote nothing");
        assertEquals(10, store.stats().flushAppended());
        chunks.forEach(chunk -> assertEquals(1, chunk.written.size(), "one record per chunk"));
    }

    @Test
    public void twoWritersRacingForTheFinalFlushWriteItExactlyOnce() throws Exception {
        int byTick = 0;
        int byShutdown = 0;
        for (int round = 0; round < 1_000; round++) {
            final Path overlayRoot = root.resolve("round-" + round);
            final OverlayFixture.CountingDisk roundDisk = new OverlayFixture.CountingDisk();
            final OverlayStore store = open(overlayRoot, 1, roundDisk);
            final List<FakeChunk> chunks = dirty(store, 10);
            final CompletableFuture<?> requested = store.finalFlush(TWO_SECONDS, true);
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final Thread tick = Thread.ofPlatform().name("tick-end").start(() -> {
                await(barrier);
                store.tickEnd();
            });
            final Thread shutdown = Thread.ofPlatform().name("shutdown").start(() -> {
                await(barrier);
                var _ = store.finalFlush(TWO_SECONDS, false);
            });
            tick.join();
            shutdown.join();

            final int round0 = round;
            assertTrue(requested.isDone(), () -> "round " + round0);
            assertEquals(10, store.stats().flushAppended(), () -> "round " + round0);
            assertEquals(1, roundDisk.forces.get(), () -> "round " + round0);
            for (FakeChunk chunk : chunks) assertEquals(1, chunk.written.size(), () -> "round " + round0);
            final String writer = roundDisk.forceThreads.peek();
            if ("tick-end".equals(writer)) byTick++;
            else if ("shutdown".equals(writer)) byShutdown++;
            else throw new AssertionError("written by " + writer);
            deleteTree(overlayRoot);
        }
        assertTrue(byTick > 0 && byShutdown > 0, "by=tick " + byTick + ", by=shutdown " + byShutdown);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    public void changesAfterTheFinalFlushAreNotWritten() throws Exception {
        final OverlayStore store = open(1);
        final List<FakeChunk> chunks = dirty(store, 2);
        var _ = store.finalFlush(TWO_SECONDS, false);
        final int writes = disk.writes.get();
        final FakeChunk kicking = chunks.get(0);
        final FakeChunk onDisable = chunks.get(1);

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            kicking.change();
            onDisable.change();
            kicking.change();
            store.unloaded(onDisable);
            final List<String> warnings = log.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings::toString);
            assertTrue(warnings.getFirst().contains("0,0"), warnings.getFirst());
        }
        assertEquals(1, onDisable.encodes.get(), "not encoded for the unload");
        store.tickEnd();
        assertEquals(writes, disk.writes.get(), "no new record");
        final OverlayStore reopened = open(1);
        assertEquals(1, reopened.find(0, 0).generation());
        assertEquals(1, reopened.find(1, 0).generation());
    }

    @Test
    public void aFinalFlushOverItsBudgetCompletesAtTheDeadline() throws Exception {
        // Two encode threads take 10 s for the lot, whatever machine runs the test
        final OverlayStore store = OverlayStore.open(root, manifest, OverlayFixture.settings(), new OverlayOptions(16_384, 10),
                new OverlayTuning(2, 1L << 26, OverlayTuning.SEGMENT_RECORDS, OverlayFixture.TEST_PHASE_NANOS, clock, disk));
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 20_000);
        for (FakeChunk chunk : chunks) {
            chunk.encodeNanos = TimeUnit.MILLISECONDS.toNanos(1);
            chunk.change();
        }
        final AtomicLong completedAt = new AtomicLong();
        final long start = System.nanoTime();
        final CompletableFuture<?> done = store.finalFlush(Duration.ofSeconds(1), true);
        var _ = done.whenComplete((_, _) -> completedAt.set(System.nanoTime()));

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store.tickEnd();
            assertTrue(done.isDone());
            final long took = completedAt.get() - start;
            assertTrue(took >= TimeUnit.MILLISECONDS.toNanos(990) && took < TimeUnit.MILLISECONDS.toNanos(1_500),
                    () -> "the step completed after " + took / 1_000_000 + " ms");
            final List<String> errors = log.messagesAt(Level.ERROR);
            assertTrue(errors.stream().anyMatch(line -> line.startsWith("Overlay final flush did not finish within 1000 ms: ")
                    && line.endsWith(" dirty chunks left")), errors::toString);
            final long written = chunks.stream().filter(chunk -> !chunk.dirty()).count();
            assertTrue(written > 0 && written < 20_000, "written " + written);
            assertEquals(written, store.stats().flushAppended(), "what was encoded is appended");
            assertTrue(flushLine(log, Level.ERROR).contains("left=" + (20_000 - written)), errors::toString);
        }
        assertEquals(1, disk.forces.get());
        // Only an encoding that passed the deadline check just before the deadline and was then held up
        // past it, a GC pause say, starts late: at most one per encode thread
        final long late = start + TimeUnit.MILLISECONDS.toNanos(1_020);
        final List<Long> lateStarts = chunks.stream().flatMap(chunk -> chunk.encodeStarts.stream())
                .filter(at -> at - late > 0).map(at -> (at - start) / 1_000_000).sorted().toList();
        assertTrue(lateStarts.size() <= 2, () -> "encodings started this many ms in: " + lateStarts);
    }

    @Test
    public void aStuckChunkLockLeavesOnlyThatChunk() throws Exception {
        final OverlayStore store = open(1);
        final List<FakeChunk> chunks = dirty(store, 20);
        final FakeChunk stuck = chunks.get(7);
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean held = new AtomicBoolean();
        final Thread tickThread = Thread.ofPlatform().start(() -> {
            stuck.lock.lock();
            try {
                held.set(true);
                locked.countDown();
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                stuck.lock.unlock();
            }
        });
        locked.await();

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            final long start = System.nanoTime();
            final CompletableFuture<?> done = store.finalFlush(TWO_SECONDS, false);
            final long took = System.nanoTime() - start;
            assertTrue(done.isDone());
            assertTrue(took < TimeUnit.SECONDS.toNanos(2), "within the budget, " + took / 1_000_000 + " ms");
            final String line = flushLine(log, Level.ERROR);
            assertTrue(line.contains("chunks=19") && line.contains("left=1"), line);
        } finally {
            release.countDown();
            tickThread.join();
        }
        assertTrue(held.get());
        assertEquals(1, stuck.encodes.get(), "given up after 50 ms, not tried again");
        assertTrue(stuck.dirty());
        final Set<Integer> written = chunks.stream().filter(chunk -> !chunk.dirty()).map(FakeChunk::chunkX).collect(Collectors.toSet());
        assertEquals(19, written.size());
        assertFalse(written.contains(stuck.chunkX()));
    }

    /** A store whose write phases give up on their encodings after the 20 ms of a shard. */
    private OverlayStore openWithTheShardPhase() throws Exception {
        return OverlayStore.open(root, manifest, OverlayFixture.settings(), new OverlayOptions(16_384, 10),
                OverlayFixture.shardPhase(1, clock, disk));
    }
}
