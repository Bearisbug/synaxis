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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    public void aPhaseOverItsBudgetLeavesTheRestToTheNextTicks() throws Exception {
        final OverlayStore store = open(16_384, 1);
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
    public void aSegmentWhoseHeaderNeverReachedTheFileIsSkipped() throws Exception {
        final Path directory = Files.createDirectories(root.resolve(manifest.buildId()));
        Files.write(directory.resolve("seg.3.synx"), new byte[100]); // a crash right after the file was made
        final OverlayStore store;
        try (LogCapture log = LogCapture.of(OverlayStore.class)) {
            store = open(16_384, 1);
            assertEquals(1, log.messagesAt(Level.WARN).size(), log.messagesAt(Level.WARN)::toString);
        }
        new FakeChunk(store, 0, 0, 0).change();
        clock.advance(Duration.ofSeconds(10));
        store.tickEnd();
        assertEquals(4, store.find(0, 0).segment());
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
}
