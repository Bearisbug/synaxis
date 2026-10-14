package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write phase, the pending table, eviction, compaction and the start-up of the overlay store
 * ({@code docs/DESIGN-M3.md} API-M3-007, §19 {@code REQ-M3-011} overlay store unit tests), with
 * {@link FakeChunk} for the chunks.
 */
public class OverlayStoreTest {

    @TempDir
    Path root;

    private final OverlayFixture.Clock clock = new OverlayFixture.Clock();
    private final OverlayFixture.CountingDisk disk = new OverlayFixture.CountingDisk();
    private final SynxManifest manifest = OverlayFixture.manifest(776);

    private OverlayStore open(int cap, int encodeThreads) throws Exception {
        return open(cap, OverlayFixture.tuning(encodeThreads, clock, disk));
    }

    private OverlayStore open(int cap, OverlayTuning tuning) throws Exception {
        return OverlayStore.open(root, manifest, OverlayFixture.settings(), new OverlayOptions(cap, 10), tuning);
    }

    private static long generation(OverlayStore store, FakeChunk chunk) {
        final OverlayHit hit = store.find(chunk.chunkX(), chunk.chunkZ());
        assertNotNull(hit, () -> "no record of " + chunk.chunkX() + "," + chunk.chunkZ());
        final long generation = FakeChunk.generationOf(hit, chunk.chunkX(), chunk.chunkZ());
        assertEquals(hit.generation(), generation, "the record holds the generation its hit names");
        return generation;
    }

    /**
     * The 40 encodings take 5 ms each, 50 ms on the four encode threads, more than the 20 ms of a
     * shard's write phase, which leaves the ones not done by then to the next tick. The phase of the
     * tests waits for all 40 ({@link OverlayFixture#TEST_PHASE_NANOS}).
     */
    @Test
    public void aTickOfEvictionsUnloadsAndDueChunksIsOneFsync() throws Exception {
        final OverlayStore store = open(32, 4);
        final List<FakeChunk> due = OverlayFixture.chunks(store, 0, 8);
        final List<FakeChunk> evicted = OverlayFixture.chunks(store, 100, 8);
        final List<FakeChunk> unloading = OverlayFixture.chunks(store, 200, 24);
        due.forEach(FakeChunk::change); // dirty since t = 0
        clock.advance(Duration.ofSeconds(5));
        evicted.forEach(FakeChunk::change); // dirty since t = 5 s: not due at t = 10 s
        clock.advance(Duration.ofSeconds(5));
        due.forEach(FakeChunk::change); // changed again, so the least recently changed are the eight to evict
        unloading.forEach(FakeChunk::change);
        unloading.forEach(store::unloadRequested);
        final List<FakeChunk> all = Stream.of(due, evicted, unloading).flatMap(List::stream).toList();
        for (FakeChunk chunk : all) chunk.encodeNanos = TimeUnit.MILLISECONDS.toNanos(5);
        final AtomicBoolean indexedBeforeFsync = new AtomicBoolean();
        disk.onForce = () -> indexedBeforeFsync.set(all.stream().anyMatch(c -> store.find(c.chunkX(), c.chunkZ()) != null));

        store.tickEnd();

        assertEquals(1, disk.forces.get(), "one group commit");
        assertFalse(indexedBeforeFsync.get(), "the index names no record before its fsync");
        final OverlayStats stats = store.stats();
        assertEquals(40, stats.flushAppended());
        assertEquals(1, stats.flushFsyncs());
        assertEquals(8, stats.evicted());
        assertEquals(32, stats.resident());
        assertEquals(40, stats.records());
        for (FakeChunk chunk : all) {
            assertEquals(chunk.generation(), generation(store, chunk));
            assertFalse(chunk.dirty());
            assertEquals(1, chunk.written.size());
            assertEquals(evicted.contains(chunk), chunk.written.getFirst().demote());
            assertEquals(!evicted.contains(chunk), chunk.overlay());
        }
        // Appended in the order of the batch: evictions, then flushes before unload, then the due chunks
        assertTrue(maxOffset(store, evicted) < minOffset(store, unloading));
        assertTrue(maxOffset(store, unloading) < minOffset(store, due));
    }

    private static long maxOffset(OverlayStore store, List<FakeChunk> chunks) {
        return chunks.stream().mapToLong(c -> store.find(c.chunkX(), c.chunkZ()).offset()).max().orElseThrow();
    }

    private static long minOffset(OverlayStore store, List<FakeChunk> chunks) {
        return chunks.stream().mapToLong(c -> store.find(c.chunkX(), c.chunkZ()).offset()).min().orElseThrow();
    }

    /**
     * §19 「覆盖目录组可写时拒启，新建的覆盖目录是 0700」, the store part (M3-S): the two directories it makes
     * are 0700 and its segment files 0600, whatever the umask.
     */
    @Test
    public void theDirectoriesAndSegmentsTheStoreMakesAreTheServiceUsersAlone() throws Exception {
        final Path overlay = root.resolve("overlay");
        final OverlayStore store = OverlayStore.open(overlay, manifest, OverlayFixture.settings(), new OverlayOptions(16, 10),
                OverlayFixture.tuning(1, clock, disk));
        final FakeChunk chunk = OverlayFixture.chunks(store, 0, 1).getFirst();
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        assertFalse(chunk.dirty(), "written");
        final Path own = overlay.resolve(manifest.buildId());
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(overlay)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(own)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(own.resolve("seg.0.synx"))));
    }

    /**
     * F4, a kill loses at most the changes of the last {@code flush_interval_s}: the write phase that
     * takes a due chunk runs up to a tick later and then encodes and forces, so a chunk is due 250 ms
     * before its interval is up. The chunk changed at t = 0 is written by the phase at t = 9.75 s, not
     * by the one a millisecond earlier.
     */
    @Test
    public void aChunkIsDueAWritePhaseBeforeItsFlushIntervalIsUp() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = OverlayFixture.chunks(store, 0, 1).getFirst();
        chunk.change();

        clock.advance(Duration.ofMillis(10_000 - 251));
        store.tickEnd();
        assertTrue(chunk.dirty(), "not due 251 ms before its flush interval is up");
        assertEquals(0, disk.forces.get());

        clock.advance(Duration.ofMillis(1));
        store.tickEnd();
        assertFalse(chunk.dirty(), "due 250 ms before its flush interval is up");
        assertEquals(1, disk.forces.get());
        assertEquals(1, generation(store, chunk));
    }

    @Test
    public void aPhaseOverItsBudgetLeavesTheRestToTheNextTicks() throws Exception {
        final OverlayStore store = open(16_384, OverlayFixture.shardPhase(1, clock, disk));
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 1_000);
        for (FakeChunk chunk : chunks) {
            chunk.encodeNanos = TimeUnit.MILLISECONDS.toNanos(5);
            chunk.change();
        }
        clock.advance(Duration.ofSeconds(10));

        final long start = System.nanoTime();
        store.tickEnd();
        final long firstTick = System.nanoTime() - start;
        // All 64 of the tick on the one encode thread would take 320 ms
        assertTrue(firstTick < TimeUnit.MILLISECONDS.toNanos(200), () -> "the first tick took " + firstTick / 1_000_000 + " ms");
        long appended = store.stats().flushAppended();
        assertTrue(appended <= 64, "at most 64 due chunks a tick, " + appended);
        assertTrue(disk.forces.get() <= 1);

        int ticks = 1;
        while (chunks.stream().anyMatch(FakeChunk::dirty)) {
            store.tickEnd();
            ticks++;
            final long now = store.stats().flushAppended();
            assertTrue(now - appended <= 64, "tick " + ticks + " appended " + (now - appended));
            appended = now;
            assertTrue(ticks < 5_000, "the chunks never got written");
        }
        assertEquals(1_000, appended);
        assertTrue(ticks > 10, "spread over the ticks, " + ticks);
        for (FakeChunk chunk : chunks) {
            assertEquals(1, chunk.encodes.get(), "encoded once, never submitted again while in flight");
            assertEquals(1, chunk.maxRunning.get());
            assertEquals(chunk.generation(), generation(store, chunk));
        }
    }

    /**
     * The 64 due chunks a tick count the encodings earlier phases left in flight: a phase whose
     * encodings all ran over its 20 ms leaves 64 in flight, and the next tick appends those 64 and
     * submits none on top. Otherwise the tick after a stall of the tick thread appends everything
     * the encode pool finished meanwhile, more than 64.
     */
    @Test
    public void encodingsLeftInFlightTakeTheDueCapOfTheNextTick() throws Exception {
        final OverlayStore store = open(16_384, OverlayFixture.shardPhase(1, clock, disk));
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 128);
        final CountDownLatch release = new CountDownLatch(1);
        for (FakeChunk chunk : chunks) {
            chunk.inEncoding = () -> await(release);
            chunk.change();
        }
        clock.advance(Duration.ofSeconds(10));

        store.tickEnd(); // the first of the 64 holds up the one encode thread: all 64 left in flight
        assertEquals(0, store.stats().flushAppended());
        release.countDown();
        awaitEncoded(chunks, 64);

        store.tickEnd();

        assertEquals(64, store.stats().flushAppended(), "the 64 left in flight, and none on top");
        assertEquals(64, chunks.stream().filter(c -> c.encodes.get() == 0).count(),
                "nothing submitted while 64 were in flight");
    }

    /** The same for the 64 evictions and the 256 flushes before unload a tick. */
    @Test
    public void encodingsLeftInFlightTakeTheEvictionAndUnloadCapsOfTheNextTick() throws Exception {
        final OverlayStore store = open(300, OverlayFixture.shardPhase(1, clock, disk));
        final List<FakeChunk> evicting = OverlayFixture.chunks(store, 0, 200);
        final List<FakeChunk> unloading = OverlayFixture.chunks(store, 1_000, 300);
        final List<FakeChunk> all = Stream.concat(evicting.stream(), unloading.stream()).toList();
        final CountDownLatch release = new CountDownLatch(1);
        for (FakeChunk chunk : all) {
            chunk.inEncoding = () -> await(release);
            chunk.change(); // 500 residents over a cap of 300, the ones to evict changed first; none due
        }
        unloading.forEach(store::unloadRequested);

        store.tickEnd(); // 64 evictions and 256 flushes before unload, all left in flight
        assertEquals(0, store.stats().flushAppended());
        release.countDown();
        awaitEncoded(all, 64 + 256);

        store.tickEnd();

        final OverlayStats stats = store.stats();
        assertEquals(64 + 256, stats.flushAppended(), "the 320 left in flight, and none on top");
        assertEquals(64, stats.evicted());
        assertEquals(256, unloading.stream().filter(c -> !c.dirty()).count());
        assertEquals(136 + 44, all.stream().filter(c -> c.encodes.get() == 0).count(),
                "nothing submitted while the caps were in flight");
    }

    /** Until {@code count} encodings of {@code chunks} have returned. */
    private static void awaitEncoded(List<FakeChunk> chunks, int count) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (chunks.stream().mapToInt(c -> c.encodesDone.get()).sum() < count) {
            assertTrue(System.nanoTime() - deadline < 0, "the encodings never returned");
            Thread.sleep(1);
        }
    }

    @Test
    public void aBatchPastTheEndOfItsSegmentGoesOnInANewOneAndForcesEach() throws Exception {
        // Room for three records of a FakeChunk, 600 bytes each with their record header
        final OverlayStore store = open(16_384, new OverlayTuning(1, SynxFormat.HEADER_BYTES + 3 * 600,
                OverlayTuning.SEGMENT_RECORDS, OverlayFixture.TEST_PHASE_NANOS, clock, disk));
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 7);
        chunks.forEach(FakeChunk::change);
        clock.advance(Duration.ofSeconds(10));

        store.tickEnd();

        assertEquals(List.of(0, 0, 0, 1, 1, 1, 2), chunks.stream().map(c -> store.find(c.chunkX(), 0).segment()).toList());
        assertEquals(3, disk.forces.get());
        assertEquals(3, store.stats().flushFsyncs(), "fsync calls");
        final OverlayStore reopened = open(16_384, 1);
        for (FakeChunk chunk : chunks) assertEquals(1, generation(reopened, chunk));
    }

    @Test
    public void aChunkChangedAfterItsEncodingStaysDirtyAndIsWrittenAgain() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 3, 4, 0);
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        final AtomicBoolean changed = new AtomicBoolean();
        disk.onForce = () -> {
            if (changed.compareAndSet(false, true)) chunk.change();
        };

        store.tickEnd();

        assertEquals(1, generation(store, chunk), "the record of the encoding is indexed all the same");
        assertEquals(2, chunk.generation());
        assertTrue(chunk.dirty());
        assertTrue(chunk.overlay());
        assertEquals(1, store.stats().resident());

        store.tickEnd();

        assertEquals(2, generation(store, chunk));
        assertFalse(chunk.dirty());
        assertEquals(2, disk.forces.get(), "a phase appending to the segment an earlier phase made forces it too");
    }

    @Test
    public void aChunkThatDoesNotEncodeStaysDirtyWhileTheOthersAreWritten() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 3);
        chunks.forEach(FakeChunk::change);
        chunks.get(0).encoding = FakeChunk.Encoding.THROW;
        chunks.get(1).encoding = FakeChunk.Encoding.NULL;
        clock.advance(Duration.ofSeconds(10));

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store.tickEnd();
            final List<String> errors = log.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors::toString);
            assertTrue(errors.getFirst().contains("0,0"), errors::toString);
        }
        assertTrue(chunks.get(0).dirty());
        assertNull(store.find(0, 0));
        assertTrue(chunks.get(1).dirty());
        assertNull(store.find(1, 0));
        assertEquals(1, generation(store, chunks.get(2)));

        chunks.get(0).encoding = FakeChunk.Encoding.NORMAL;
        chunks.get(1).encoding = FakeChunk.Encoding.NORMAL;
        store.tickEnd();
        assertEquals(1, generation(store, chunks.get(0)));
        assertEquals(1, generation(store, chunks.get(1)));
    }

    @Test
    public void aCleanChunkIsEvictedWithoutANewRecord() throws Exception {
        final OverlayStore store = open(2, 1);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 3);
        chunks.get(0).change();
        chunks.get(1).change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        assertFalse(chunks.get(0).dirty());
        final OverlayHit indexed = store.find(0, 0);
        final int writes = disk.writes.get();
        final int forces = disk.forces.get();

        chunks.get(2).change(); // three in overlay mode, the cap is two; not due
        store.tickEnd();

        final FakeChunk evicted = chunks.getFirst();
        assertFalse(evicted.overlay(), "back to handle mode");
        final FakeChunk.Written written = evicted.written.getLast();
        assertTrue(written.demote());
        assertSame(indexed, written.hit(), "demoted onto the record it was written to before");
        assertEquals(1, written.generation());
        assertEquals(writes, disk.writes.get(), "no record appended");
        assertEquals(forces, disk.forces.get(), "no fsync");
        assertEquals(1, evicted.encodes.get(), "not encoded again");
        final OverlayStats stats = store.stats();
        assertEquals(2, stats.resident());
        assertEquals(1, stats.evicted());
        assertTrue(chunks.get(1).overlay());
    }

    @Test
    public void aCleanChunkThatDoesNotTakeItsDemotionStaysAResident() throws Exception {
        final OverlayStore store = open(2, 1);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 3);
        chunks.get(0).change();
        chunks.get(1).change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        chunks.get(2).change(); // three in overlay mode, the cap is two
        final FakeChunk evicted = chunks.getFirst();
        evicted.refuseWritten = true; // its write-lock held for 50 ms, say

        store.tickEnd();

        assertTrue(evicted.written.getLast().demote(), "asked to go back to handle mode");
        assertTrue(evicted.overlay());
        OverlayStats stats = store.stats();
        assertEquals(3, stats.resident(), "still in overlay mode, still counted");
        assertEquals(0, stats.evicted());

        evicted.refuseWritten = false;
        store.tickEnd();

        assertFalse(evicted.overlay(), "evicted by the next tick");
        stats = store.stats();
        assertEquals(2, stats.resident());
        assertEquals(1, stats.evicted());
    }

    @Test
    public void aDirtyChunkThatDoesNotTakeItsRecordIsWrittenAgain() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 0, 0, 0);
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        chunk.refuseWritten = true;

        store.tickEnd();

        assertEquals(1, generation(store, chunk), "indexed all the same");
        assertTrue(chunk.dirty());
        chunk.refuseWritten = false;
        store.tickEnd();
        assertFalse(chunk.dirty());
        assertEquals(2, store.stats().flushAppended());
    }

    @Test
    public void anExplicitlyUnloadedDirtyChunkIsEncodedAtOnceAndWrittenByTheNextTick() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 7, -3, 0);
        chunk.change();
        chunk.change();
        chunk.change();

        store.unloaded(chunk);

        assertEquals(1, chunk.encodes.get());
        assertEquals(Thread.currentThread().getName(), chunk.encodeThreads.peek(), "encoded on the unloading thread");
        final OverlayHit pending = store.find(7, -3);
        assertNotNull(pending);
        assertEquals(-1, pending.segment(), "from the pending table");
        assertEquals(3, pending.generation());
        assertEquals(3, FakeChunk.generationOf(pending, 7, -3));
        assertEquals(0, store.stats().resident());
        assertEquals(0, disk.writes.get(), "nothing written on the unloading thread");

        final AtomicReference<OverlayHit> duringFsync = new AtomicReference<>();
        disk.onForce = () -> duringFsync.set(store.find(7, -3));
        store.tickEnd();

        assertSame(pending, duringFsync.get(), "the pending table answers until the record is indexed");
        final OverlayHit written = store.find(7, -3);
        assertNotNull(written);
        assertEquals(0, written.segment());
        assertEquals(3, generation(store, chunk));
        assertTrue(chunk.written.isEmpty(), "a pending entry has no chunk to tell");
        store.tickEnd();
        assertEquals(1, store.stats().flushAppended(), "out of the pending table once written");

        final OverlayStore reopened = open(16_384, 1);
        assertEquals(3, generation(reopened, chunk));
    }

    @Test
    public void aSecondUnloadOfTheSameChunkReplacesItsPendingEncoding() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk first = new FakeChunk(store, 1, 1, 0);
        first.change();
        store.unloaded(first);
        final FakeChunk second = new FakeChunk(store, 1, 1, 1); // loaded again from the pending entry
        second.change();
        store.unloaded(second);

        assertEquals(2, store.find(1, 1).generation());
        store.tickEnd();
        assertEquals(1, store.stats().flushAppended(), "the replaced entry is not written");
        assertEquals(2, generation(store, second));
    }

    @Test
    public void anUnloadThatCannotTakeTheWriteLockDropsTheChangeWithAnError() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 5, -6, 0);
        chunk.change();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = Thread.ofPlatform().start(() -> {
            chunk.lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                chunk.lock.unlock();
            }
        });
        locked.await();
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store.unloaded(chunk);
            assertEquals(1, log.messagesAt(Level.ERROR).size());
            assertTrue(log.messagesAt(Level.ERROR).getFirst().contains("5,-6"), log.messagesAt(Level.ERROR)::toString);
        } finally {
            release.countDown();
            holder.join();
        }
        assertNull(store.find(5, -6));
    }

    @Test
    public void anUnloadThatDoesNotEncodeLeavesThePendingEntryItWouldHaveReplaced() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk first = new FakeChunk(store, 8, 8, 0);
        first.change();
        store.unloaded(first);
        final FakeChunk second = new FakeChunk(store, 8, 8, 1); // loaded again from the pending entry
        second.change();
        second.encoding = FakeChunk.Encoding.THROW;

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store.unloaded(second);
            assertEquals(1, log.messagesAt(Level.ERROR).size(), log.messagesAt(Level.ERROR)::toString);
        }
        final OverlayHit hit = store.find(8, 8);
        assertEquals(-1, hit.segment());
        assertEquals(1, hit.generation(), "the first unload's encoding is still the latest content there is");
        store.tickEnd();
        assertEquals(1, generation(store, first));
    }

    @Test
    public void aLoadWhileAnUnloadEncodesTheChunkWaitsForTheEncoding() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 6, 6, 0);
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd(); // generation 1 in the index
        chunk.change();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        chunk.inEncoding = () -> {
            entered.countDown();
            await(release);
        };
        final Thread unloading = Thread.ofPlatform().start(() -> store.unloaded(chunk));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        final CompletableFuture<OverlayHit> load = CompletableFuture.supplyAsync(() -> store.find(6, 6),
                Thread::startVirtualThread);
        assertThrows(TimeoutException.class, () -> load.get(200, TimeUnit.MILLISECONDS),
                "the load waits for the unload's encoding instead of taking the index's older record");
        release.countDown();
        unloading.join();
        assertEquals(2, load.get(5, TimeUnit.SECONDS).generation());
    }

    @Test
    public void aChunkLoadedAgainBeforeItsOldObjectLeftIsTrackedOnItsOwn() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk old = new FakeChunk(store, 4, 4, 0);
        old.change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        // The instance dropped the old object and loaded the chunk again before ChunkLoader#unloadChunk told the store
        final FakeChunk loaded = new FakeChunk(store, 4, 4, 1);
        loaded.change();
        store.unloaded(old);

        assertEquals(1, store.stats().resident(), "the new object stays a resident");
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        assertFalse(loaded.dirty());
        assertEquals(2, generation(store, loaded));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void aCleanChunkUnloadedOnlyLeavesTheResidents() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 0, 0, 0);
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        assertEquals(1, store.stats().resident());

        store.unloaded(chunk);

        assertEquals(0, store.stats().resident());
        assertEquals(1, chunk.encodes.get(), "not encoded for the unload");
        store.tickEnd();
        assertEquals(1, store.stats().flushAppended());
    }

    @Test
    public void aFailedWriteKeepsEveryChunkDirtyAndIsRetriedByTheNextTick() throws Exception {
        final OverlayStore store = open(1, 1);
        final FakeChunk evicted = new FakeChunk(store, 0, 0, 0);
        final FakeChunk due = new FakeChunk(store, 1, 0, 0);
        evicted.change();
        due.change();
        clock.advance(Duration.ofSeconds(10));
        disk.failWrite = new IOException("No space left on device");

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store.tickEnd();
            assertEquals(1, log.messagesAt(Level.ERROR).size(), log.messagesAt(Level.ERROR)::toString);
        }
        assertNull(store.find(0, 0));
        assertNull(store.find(1, 0));
        assertTrue(evicted.written.isEmpty());
        assertTrue(due.written.isEmpty());
        assertTrue(evicted.dirty() && evicted.overlay());
        assertTrue(due.dirty() && due.overlay());
        OverlayStats stats = store.stats();
        assertEquals(1, stats.evictFailed());
        assertEquals(2, stats.resident());

        disk.failWrite = null;
        disk.failForce = new IOException("Input/output error");
        store.tickEnd();
        assertNull(store.find(0, 0), "not indexed without its fsync");
        assertTrue(evicted.dirty());
        assertEquals(2, store.stats().evictFailed());

        disk.failForce = null;
        store.tickEnd();
        assertEquals(1, generation(store, evicted));
        assertEquals(1, generation(store, due));
        assertFalse(evicted.overlay(), "evicted once written");
        assertFalse(due.dirty());
        stats = store.stats();
        assertEquals(1, stats.resident());
        assertEquals(1, stats.evicted());

        final OverlayStore reopened = open(1, 1);
        assertEquals(1, generation(reopened, evicted));
        assertEquals(1, generation(reopened, due));
    }

    @Test
    public void aSegmentMostlyOverwrittenIsCopiedForwardAndUnlinked() throws Exception {
        final OverlayStore store = open(16_384, OverlayFixture.tuning(1, 8, clock, disk));
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 8);
        chunks.forEach(FakeChunk::change);
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd(); // eight records seal segment 0
        final OverlayHit before = store.find(6, 0);
        chunks.subList(0, 5).forEach(FakeChunk::change);
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd(); // five of the eight overwritten in segment 1

        assertTrue(Files.exists(OverlayFixture.segment(root, manifest, 0)));
        store.tickEnd(); // the three left are copied into segment 1, and segment 0 goes

        assertFalse(Files.exists(OverlayFixture.segment(root, manifest, 0)));
        for (FakeChunk chunk : chunks.subList(5, 8)) {
            final OverlayHit hit = store.find(chunk.chunkX(), 0);
            assertEquals(1, hit.segment());
            assertEquals(1, generation(store, chunk), "a copy keeps its generation");
        }
        assertEquals(1, FakeChunk.generationOf(before, 6, 0), "a record of the unlinked segment still reads");
        final OverlayStore reopened = open(16_384, 1);
        for (FakeChunk chunk : chunks) assertEquals(chunk.generation(), generation(reopened, chunk));
        assertEquals(8, reopened.stats().records());
    }

    @Test
    public void aRestartDropsAHalfWrittenTailAndWritesIntoANewSegment() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 3);
        chunks.forEach(FakeChunk::change);
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        final FakeChunk torn = chunks.get(2);
        torn.change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        final OverlayHit last = store.find(torn.chunkX(), 0);
        assertEquals(2, last.generation());
        // A crash halfway through the last record: from the middle of its segment ① on, nothing reached the file
        final Path segment = OverlayFixture.segment(root, manifest, 0);
        final long cut = last.offset() + OverlaySegment.RECORD_HEADER_BYTES + SynxFormat.SEGMENT_TABLE_BYTES + 8;
        final long end = last.offset() + OverlaySegment.RECORD_HEADER_BYTES + SynxFormat.SEGMENT_TABLE_BYTES
                + last.record().packet().byteSize() + SynxFormat.NAVIGATION_BYTES;
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.allocate((int) (end - cut)), cut);
        }
        final byte[] segmentBytes = Files.readAllBytes(segment);

        final OverlayStore reopened;
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            reopened = open(16_384, 1);
            final List<String> errors = log.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors::toString);
            assertTrue(errors.getFirst().contains("seg.0.synx") && errors.getFirst().contains(Long.toString(last.offset())),
                    errors::toString);
        }
        assertEquals(1, generation(reopened, torn), "back to the last whole record");
        assertEquals(1, generation(reopened, chunks.get(0)));
        assertEquals(1, generation(reopened, chunks.get(1)));

        final FakeChunk next = new FakeChunk(reopened, 0, 0, 1);
        next.change();
        clock.advance(Duration.ofSeconds(10));
        reopened.tickEnd();
        assertEquals(1, reopened.find(0, 0).segment(), "a new segment, never the old one");
        assertArrayEquals(segmentBytes, Files.readAllBytes(segment), "the old segment is left as it was");
    }

    @Test
    public void aNewBuildIdClearsTheOverlay() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 10);
        chunks.forEach(FakeChunk::change);
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        assertEquals(10, store.stats().records());
        final Path old = root.resolve(manifest.buildId());
        assertTrue(Files.isDirectory(old));

        final SynxManifest bumped = OverlayFixture.manifest(777); // only the protocol went up
        final OverlayStore next = OverlayStore.open(root, bumped, OverlayFixture.settings(777, OverlayFixture.REGISTRY_DIGEST),
                new OverlayOptions(16_384, 10), OverlayFixture.tuning(1, clock, disk));

        assertFalse(Files.exists(old));
        assertTrue(Files.isDirectory(root.resolve(bumped.buildId())));
        assertEquals(0, next.stats().records());
        for (FakeChunk chunk : chunks) assertNull(next.find(chunk.chunkX(), 0));
    }

    @Test
    public void aStoreThatNeverWritesLeavesNoSegment() throws Exception {
        final OverlayStore store = open(16_384, 1);
        store.tickEnd();
        try (Stream<Path> files = Files.list(root.resolve(manifest.buildId()))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    public void theSegmentHeaderIsAnOverlayHeaderOfThisRelease() throws Exception {
        final OverlayStore store = open(16_384, 1);
        new FakeChunk(store, 0, 0, 0).change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();

        final byte[] bytes = Files.readAllBytes(OverlayFixture.segment(root, manifest, 0));
        final ByteBuffer header = ByteBuffer.wrap(bytes, 0, SynxFormat.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(SynxFormat.MAGIC, header.getInt(0));
        assertEquals(SynxFormat.FLAG_OVERLAY, header.getShort(6), "overlay, and no segment ④");
        assertEquals(776, header.getInt(8));
        final byte[] contentHash = new byte[32];
        header.get(64, contentHash);
        assertArrayEquals(OverlayFixture.CONTENT_HASH, contentHash);
        assertEquals(1L << 20, bytes.length, "the segment is made at its full size");
    }

    @Test
    public void aSegmentOfAnotherProcessOrReleaseRefusesTheStart() throws Exception {
        final OverlayStore store = open(16_384, 1);
        new FakeChunk(store, 0, 0, 0).change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        final OverlayOptions options = new OverlayOptions(16_384, 10);
        final OverlayTuning tuning = OverlayFixture.tuning(1, clock, disk);

        assertEquals(SynxError.PROTOCOL_MISMATCH, assertThrows(SynxException.class, () -> OverlayStore.open(root,
                manifest, OverlayFixture.settings(777, OverlayFixture.REGISTRY_DIGEST), options, tuning)).error());
        assertEquals(SynxError.REGISTRY_MISMATCH, assertThrows(SynxException.class, () -> OverlayStore.open(root,
                manifest, OverlayFixture.settings(776, new byte[32]), options, tuning)).error());
        final SynxManifest otherContent = OverlayFixture.manifest(manifest.buildId(), new byte[32], 776);
        final SynxException content = assertThrows(SynxException.class, () -> OverlayStore.open(root, otherContent,
                OverlayFixture.settings(), options, tuning));
        assertEquals(SynxError.MANIFEST, content.error());
        assertTrue(content.getMessage().contains("seg.0.synx"), content.getMessage());

        // A region file where a segment should be
        final Path segment = OverlayFixture.segment(root, manifest, 0);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0, (short) SynxFormat.FLAG_HAS_COLUMNS), 6);
        }
        assertEquals(SynxError.MAGIC, assertThrows(SynxException.class, () -> OverlayStore.open(root, manifest,
                OverlayFixture.settings(), options, tuning)).error());
    }

    @Test
    public void aSegmentWhoseHeaderNeverReachedTheFileIsDeleted() throws Exception {
        final Path directory = Files.createDirectories(root.resolve(manifest.buildId()));
        // A crash right after the file was made, and one before its first fsync
        final Path shorter = Files.write(directory.resolve("seg.3.synx"), new byte[100]);
        final Path zeros = Files.write(directory.resolve("seg.4.synx"), new byte[4_096]);
        final OverlayStore store;
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store = open(16_384, 1);
            final List<String> warnings = log.messagesAt(Level.WARN);
            assertEquals(2, warnings.size(), warnings::toString);
            assertTrue(warnings.stream().allMatch(line -> line.contains("deleted")), warnings::toString);
        }
        assertFalse(Files.exists(shorter));
        assertFalse(Files.exists(zeros));
        new FakeChunk(store, 0, 0, 0).change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        assertEquals(5, store.find(0, 0).segment(), "numbered after the files deleted");
    }

    @Test
    public void statsCountSinceTheOpenAndTheMaximaSinceTheLastCall() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 4);
        chunks.forEach(FakeChunk::change);
        clock.advance(Duration.ofSeconds(12));
        store.tickEnd();

        final OverlayStats stats = store.stats();
        assertEquals(4, stats.resident());
        assertEquals(4, stats.flushAppended());
        assertEquals(1, stats.flushFsyncs());
        assertEquals(12.0, stats.flushLagSMax(), 0.001, "dirty for 12 s when written");
        assertTrue(stats.writePhaseMsMax() > 0);
        assertEquals(4, stats.records());
        final OverlayStats again = store.stats();
        assertEquals(0, again.flushLagSMax());
        assertEquals(0, again.writePhaseMsMax());
        assertEquals(4, again.flushAppended());
    }

    @Test
    public void findSeesWholeRecordsWhileTheWritePhaseReplacesThem() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            readers.add(Thread.ofVirtual().start(() -> {
                long seen = 0;
                try {
                    while (!done.get()) {
                        final OverlayHit hit = store.find(9, 9);
                        if (hit == null) continue;
                        final long generation = FakeChunk.generationOf(hit, 9, 9);
                        if (generation != hit.generation() || generation < seen) {
                            throw new AssertionError("generation " + generation + " in a hit of " + hit.generation()
                                    + " after " + seen);
                        }
                        seen = generation;
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            }));
        }
        FakeChunk chunk = new FakeChunk(store, 9, 9, 0);
        for (int i = 0; i < 200; i++) {
            chunk.change();
            if (i % 3 == 0) {
                // Through the pending table now and then, loaded again from it
                store.unloaded(chunk);
                chunk = new FakeChunk(store, 9, 9, chunk.generation());
            }
            clock.advance(Duration.ofSeconds(10));
            store.tickEnd();
        }
        done.set(true);
        for (Thread reader : readers) reader.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(200, generation(store, chunk));
    }

    // ---- The disk cap (M3-S, API-M3-007 磁盘上限): records of FakeChunk are 600 bytes with their header

    private static final int RECORD = 600;
    private static final int HEADER = SynxFormat.HEADER_BYTES;

    private OverlayStore openCapped(long maxBytes, int segmentRecords) throws Exception {
        return OverlayStore.open(root, manifest, OverlayFixture.settings(), new OverlayOptions(16_384, 10, maxBytes),
                OverlayFixture.tuning(1, segmentRecords, clock, disk));
    }

    /** The chunks changed and due, then one write phase. */
    private void writeOnce(OverlayStore store, List<FakeChunk> chunks) {
        chunks.forEach(FakeChunk::change);
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
    }

    /**
     * {@code overlay_bytes} are what the segment files hold written, the header and the records, not the
     * length of the sparse file a segment is made at.
     */
    @Test
    public void theBytesWrittenAreWhatTheSegmentsHoldNotTheirSparseLength() throws Exception {
        final OverlayStore store = openCapped(1L << 30, 4);
        assertEquals(0, store.stats().writtenBytes(), "no segment yet");
        writeOnce(store, OverlayFixture.chunks(store, 0, 4));
        final OverlayStats stats = store.stats();
        assertEquals(HEADER + 4L * RECORD, stats.writtenBytes());
        assertFalse(stats.full());
        assertEquals(1L << 20, Files.size(OverlayFixture.segment(root, manifest, 0)), "the file at its made length, sparse");
    }

    /** §19 「整理之后降回上限以下即恢复可写」. */
    @Test
    public void compactionBelowTheCapMakesTheStoreWritableAgain() throws Exception {
        final long cap = 2L * HEADER + 7L * RECORD;
        final OverlayStore store = openCapped(cap, 4);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 4);
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            writeOnce(store, chunks);
            assertFalse(store.full(), "segment 0 sealed with four records");
            writeOnce(store, chunks.subList(0, 3));
            assertTrue(store.full(), "three of them again in segment 1: the cap");
            assertEquals(cap, store.stats().writtenBytes());
            store.tickEnd();
            assertFalse(Files.exists(OverlayFixture.segment(root, manifest, 0)), "the fourth copied, segment 0 unlinked");
            assertEquals(HEADER + 4L * RECORD, store.stats().writtenBytes());
            assertFalse(store.full(), "below the cap again");
            assertEquals(List.of("Overlay store no longer full: " + (HEADER + 4L * RECORD) + " of " + cap + " bytes written for "
                    + manifest.buildId() + ", changes to the map are taken again"), log.messagesAt(Level.INFO).stream()
                    .filter(line -> line.startsWith("Overlay store no longer full")).toList());
            assertEquals(1, log.messagesAt(Level.ERROR).size(), log.messagesAt(Level.ERROR).toString());
        }
    }

    /** §19 「起服时覆盖层已超上限」: about 80 KiB of records under a cap of 65,536 bytes. */
    @Test
    public void aStoreOverItsCapAtStartUpOpensFull() throws Exception {
        final OverlayStore first = openCapped(1L << 30, OverlayTuning.SEGMENT_RECORDS);
        writeOnce(first, OverlayFixture.chunks(first, 0, 136));
        first.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);
        final long written = HEADER + 136L * RECORD;
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            final OverlayStore reopened = openCapped(65_536, OverlayTuning.SEGMENT_RECORDS);
            assertEquals(136, reopened.stats().records(), "the index is rebuilt as ever");
            assertTrue(reopened.full(), "full from the start, not refused");
            assertEquals(written, reopened.stats().writtenBytes());
            assertEquals(List.of("Overlay store full: " + written + " of 65536 bytes written for " + manifest.buildId()
                    + ", changes to the map are refused"), log.messagesAt(Level.ERROR));
        }
    }

    /**
     * API-M3-007 启动: a segment whose last live records the final flush wrote over is no record's of
     * the index; the next start unlinks it before it judges the cap, as no write of an empty batch would.
     */
    @Test
    public void aSegmentTheFinalFlushWroteOverIsUnlinkedAtTheNextStart() throws Exception {
        final OverlayStore store = openCapped(1L << 30, 2);
        final List<FakeChunk> chunks = OverlayFixture.chunks(store, 0, 2);
        writeOnce(store, chunks); // two records seal segment 0
        chunks.forEach(FakeChunk::change);
        store.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS); // both again, in segment 1
        assertTrue(Files.exists(OverlayFixture.segment(root, manifest, 0)), "the final flush unlinks nothing");

        final OverlayStore reopened = openCapped(1L << 30, 2);

        assertFalse(Files.exists(OverlayFixture.segment(root, manifest, 0)), "no record of segment 0 is the latest");
        assertEquals(HEADER + 2L * RECORD, reopened.stats().writtenBytes(), "overlay_bytes without segment 0");
        for (FakeChunk chunk : chunks) assertEquals(2, generation(reopened, chunk));
    }

    /**
     * A full volume fails every write phase, 20 a second: the run logs one ERROR, its first, and one
     * INFO with its length once a write succeeds again; {@code evict_failed} counts every one (API-M3-007).
     */
    @Test
    public void aRunOfFailedWritePhasesLogsItsFirstFailureAndItsEnd() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 0, 0, 0);
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        disk.failWrite = new IOException("No space left on device");

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            for (int i = 0; i < 3; i++) store.tickEnd();
            assertEquals(3, store.stats().evictFailed());
            assertEquals(1, log.messagesAt(Level.ERROR).size(), log.messagesAt(Level.ERROR)::toString);
            disk.failWrite = null;
            store.tickEnd();
            assertFalse(chunk.dirty(), "written once the disk takes it");
            assertEquals(List.of("Overlay writes succeed again in " + root.resolve(manifest.buildId())
                    + " after 3 failed write phases"), log.messagesAt(Level.INFO).stream()
                    .filter(line -> line.startsWith("Overlay writes succeed again")).toList());
        }
    }

    /**
     * A chunk whose encoding throws for as long as its content stays, every write phase: one ERROR for
     * the run, with its coordinates, and one INFO once it encodes again (API-M3-007 第 4 步).
     */
    @Test
    public void aChunkThatKeepsFailingToEncodeLogsItsFirstFailureAndItsEnd() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 5, 0, 0);
        chunk.change();
        chunk.encoding = FakeChunk.Encoding.THROW;
        clock.advance(Duration.ofSeconds(10));

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            for (int i = 0; i < 3; i++) store.tickEnd();
            assertEquals(3, chunk.encodes.get(), "encoded again by every phase");
            assertTrue(chunk.dirty());
            final List<String> errors = log.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors::toString);
            assertTrue(errors.getFirst().contains("5,0"), errors::toString);
            chunk.encoding = FakeChunk.Encoding.NORMAL;
            store.tickEnd();
            assertFalse(chunk.dirty());
            assertEquals(List.of("Chunk 5,0 encodes into an overlay record again after 3 failed encodings"),
                    log.messagesAt(Level.INFO).stream().filter(line -> line.startsWith("Chunk 5,0")).toList());
        }
    }

    /**
     * The final flush is not retried: its failure is logged even in a run of failed write phases, and
     * says the changes are lost, not that they are written again.
     */
    @Test
    public void aFinalFlushThatCannotWriteSaysItsChangesAreLost() throws Exception {
        final OverlayStore store = open(16_384, 1);
        final FakeChunk chunk = new FakeChunk(store, 0, 0, 0);
        chunk.change();
        clock.advance(Duration.ofSeconds(10));
        disk.failWrite = new IOException("No space left on device");

        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store.tickEnd(); // a run of failed write phases begins
            store.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);
            final List<String> errors = log.messagesAt(Level.ERROR);
            assertEquals(3, errors.size(), errors::toString);
            assertTrue(errors.get(0).startsWith("The overlay write phase failed"), errors::toString);
            assertTrue(errors.get(1).startsWith("The overlay final flush failed")
                    && errors.get(1).endsWith("their changes are lost when the process exits"), errors::toString);
            assertTrue(errors.get(2).startsWith("Overlay final flush: chunks=0 bytes=0 fsync_ms=0 left=1"), errors::toString);
        }
        assertTrue(chunk.dirty());
    }

    /**
     * A batch whose first record does not fit behind the last one of a segment the count has not
     * sealed goes on in a new segment; the full one is sealed then, its channel closed.
     */
    @Test
    public void aFullSegmentTheNextBatchDoesNotGoIntoIsSealed() throws Exception {
        final List<FileChannel> channels = new ArrayList<>();
        final OverlayTuning.Disk recording = new OverlayTuning.Disk() {
            @Override
            public void write(FileChannel channel, ByteBuffer bytes, long position) throws IOException {
                if (!channels.contains(channel)) channels.add(channel);
                disk.write(channel, bytes, position);
            }

            @Override
            public void force(FileChannel channel) throws IOException {
                disk.force(channel);
            }
        };
        // Room for three records, and far more records than three before the count seals a segment
        final OverlayStore store = open(16_384, new OverlayTuning(1, HEADER + 3 * RECORD, OverlayTuning.SEGMENT_RECORDS,
                OverlayFixture.TEST_PHASE_NANOS, clock, recording));
        writeOnce(store, OverlayFixture.chunks(store, 0, 3)); // segment 0 full, and not sealed
        assertTrue(channels.getFirst().isOpen(), "segment 0 still takes records");

        writeOnce(store, OverlayFixture.chunks(store, 10, 1));

        assertEquals(1, store.find(10, 0).segment(), "the record goes into segment 1");
        assertEquals(2, channels.size());
        assertFalse(channels.getFirst().isOpen(), "segment 0 sealed, its channel closed");
    }

    /**
     * §11.1 LRU 淘汰, {@code relight_pending = 0} when the chunk goes back to handle mode: a light
     * recomputation queued on a chunk after its eviction was picked, while it encoded, keeps it a
     * resident, written and clean; the next eviction demotes it on that record without encoding it.
     */
    @Test
    public void anEvictedChunkALightRecomputationWaitsForIsWrittenButNotDemoted() throws Exception {
        final OverlayStore store = open(1, 1);
        final RelitChunk evicted = new RelitChunk(store, 0, 0);
        final FakeChunk other = new FakeChunk(store, 1, 0, 0);
        evicted.chunk.change();
        other.change(); // two residents over a cap of 1, the one changed first to evict; none due
        evicted.chunk.inEncoding = () -> evicted.relightPending.set(true);

        store.tickEnd();

        assertEquals(1, generation(store, evicted.chunk), "written");
        assertFalse(evicted.chunk.dirty());
        assertEquals(List.of(false), evicted.chunk.written.stream().map(FakeChunk.Written::demote).toList());
        assertTrue(evicted.chunk.overlay(), "still a resident");
        OverlayStats stats = store.stats();
        assertEquals(0, stats.evicted());
        assertEquals(2, stats.resident());

        evicted.relightPending.set(false);
        store.tickEnd();

        assertFalse(evicted.chunk.overlay(), "demoted once nothing waits to install into it");
        assertEquals(1, evicted.chunk.encodes.get(), "on the record it was written to, not encoded again");
        stats = store.stats();
        assertEquals(1, stats.evicted());
        assertEquals(1, stats.flushAppended());
    }

    /** A {@link FakeChunk} a light recomputation can wait to install into; the store tracks this object in its place. */
    private static final class RelitChunk implements OverlayChunk {
        final FakeChunk chunk;
        final AtomicBoolean relightPending = new AtomicBoolean();

        RelitChunk(OverlayStore store, int chunkX, int chunkZ) {
            this.chunk = new FakeChunk(new OverlayTracker() {
                @Override
                public void materialized(OverlayChunk changed) {
                    store.materialized(RelitChunk.this);
                }

                @Override
                public void modified(OverlayChunk changed) {
                    store.modified(RelitChunk.this);
                }

                @Override
                public void unloadRequested(OverlayChunk changed) {
                    store.unloadRequested(RelitChunk.this);
                }

                @Override
                public void unloaded(OverlayChunk changed) {
                    store.unloaded(RelitChunk.this);
                }
            }, chunkX, chunkZ, 0);
        }

        @Override
        public int chunkX() {
            return chunk.chunkX();
        }

        @Override
        public int chunkZ() {
            return chunk.chunkZ();
        }

        @Override
        public long generation() {
            return chunk.generation();
        }

        @Override
        public boolean dirty() {
            return chunk.dirty();
        }

        @Override
        public OverlayEncoding encodeOverlay(SynxSettings settings, long deadlineNanos) {
            return chunk.encodeOverlay(settings, deadlineNanos);
        }

        @Override
        public boolean written(long generation, OverlayHit hit, boolean demote) {
            return chunk.written(generation, hit, demote);
        }

        @Override
        public boolean relightPending() {
            return relightPending.get();
        }
    }
}
