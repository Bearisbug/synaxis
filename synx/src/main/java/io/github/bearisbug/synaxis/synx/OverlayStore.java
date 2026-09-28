package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The overlay store of one channel ({@code docs/DESIGN-M3.md} API-M3-007): the chunks the game
 * changed, as append only segment files under {@code <overlay root>/<build_id>/} and an index in
 * memory. The region files of the release are never written.
 * <p>
 * Chunks tell the store about their overlay life through {@link OverlayTracker}; the chunk loader
 * looks up their latest record through {@link OverlayIndex}. Writing happens in one place, the write
 * phase the shard host runs at the end of every tick ({@link #tickEnd()}): it picks the batch
 * (encodings of chunks unloaded dirty, evictions over the cap, flushes before unload, chunks dirty
 * for {@code flush_interval_s} less a write phase, compaction), encodes on a pool of platform threads for at most 20 ms,
 * appends in order, makes it durable with one {@code fsync}, and only then points the index at the
 * new records and tells the chunks. At shutdown {@link #finalFlush} writes every dirty chunk once
 * more, done by the write phase while the tick runs and by the shutdown thread once the watchdog
 * judged the tick dead; the write right decides which one, and after it nothing is written.
 */
public final class OverlayStore implements OverlayIndex, OverlayTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(OverlayStore.class);
    private static final Pattern SEGMENT_NAME = Pattern.compile("seg\\.(0|[1-9][0-9]{0,8})\\.synx");

    private static final int EVICT_PER_TICK = 64;
    private static final int UNLOAD_FLUSH_PER_TICK = 256;
    private static final int FLUSH_PER_TICK = 64;
    private static final int COPY_PER_TICK = 64;
    private static final long PHASE_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    /**
     * How long before its {@code flush_interval_s} is up a dirty chunk is due, so that a kill loses at
     * most the changes of that interval (F4): the phase that takes it runs up to a tick (50 ms) later,
     * an encoding over the 20 ms of that phase waits for the next one, and then comes the fsync.
     */
    private static final long DUE_LEAD_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long FINAL_CAP_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long UNLOAD_ENCODE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    // The write right: whoever appends and forces holds it
    private static final int IDLE = 0;
    private static final int TICK = 1;
    private static final int SHUTDOWN = 2;
    private static final int CLOSED = 3;

    /** A record the index names: {@code fsync}ed into {@code segment}. */
    private record Indexed(OverlayHit hit, OverlaySegment segment) {
    }

    /** The encoding of a chunk unloaded with changes, until the write phase writes it. */
    private record Unloaded(OverlayHit hit, byte[] record) {
    }

    /** What an encode task made of a chunk: the record as a file holds it. */
    private record Encoded(long generation, byte[] record) {
    }

    private sealed interface Item permits PendingItem, ChunkItem, CopyItem {
    }

    private record PendingItem(long key, CompletableFuture<@Nullable Unloaded> entry, Unloaded unloaded) implements Item {
    }

    private record ChunkItem(Resident resident, Encoded encoded, boolean demote) implements Item {
    }

    private record CopyItem(long key, Indexed from) implements Item {
    }

    private record Appended(Item item, OverlaySegment segment, long offset, long generation) {
    }

    /** A batch made durable: what it appended and how long its {@code fsync} took. */
    private record Commit(long bytes, long fsyncNanos) {
    }

    /**
     * A cap a tick of the write phase, on the encodings it submits for one kind of chunk and no
     * phase has harvested yet, those earlier phases left in flight included: a batch then holds no
     * more of that kind than the cap, however many the encode pool finished between two phases.
     * The holder of the write right's.
     */
    private static final class Cap {
        private final int perTick;
        private int inFlight;

        private Cap(int perTick) {
            this.perTick = perTick;
        }

        private int left() {
            return perTick - inFlight;
        }
    }

    /** A chunk in overlay mode. */
    private static final class Resident {
        private final OverlayChunk chunk;
        private final long key;
        // Guarded by this
        private long lruKey;
        private long dirtyKey;
        private volatile long dirtySince;
        private boolean removed;
        // The holder of the write right's
        private @Nullable Future<Encoded> inFlight;
        private @Nullable Cap cap;

        private Resident(OverlayChunk chunk, long key) {
            this.chunk = chunk;
            this.key = key;
        }
    }

    private final Path directory;
    private final SynxSettings settings;
    private final SynxHeader header;
    private final OverlayOptions options;
    private final OverlayTuning tuning;
    private final ThreadPoolExecutor encoders;

    // What find reads. A pending entry is in the table while unloaded() encodes it, and completes
    // with the encoding, or with null if there is none; find waits for it
    private final Map<Long, Indexed> index = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<@Nullable Unloaded>> pending = new ConcurrentHashMap<>();
    private final AtomicLong pendingSequence = new AtomicLong();

    // The chunks in overlay mode, by object (chunks keep the identity equals of Object), by their last
    // change and by their first unwritten one. By object: the instance may load a chunk again before
    // the unload of its old object reaches unloaded(), and both are residents until then
    private final Map<OverlayChunk, Resident> residents = new ConcurrentHashMap<>();
    private final NavigableMap<Long, Resident> lru = new ConcurrentSkipListMap<>();
    private final NavigableMap<Long, Resident> dirtyQueue = new ConcurrentSkipListMap<>();
    private final Set<Resident> unloadQueue = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();

    private final AtomicInteger writeRight = new AtomicInteger(IDLE);
    private final Object rightReleased = new Object();
    private volatile boolean finalRequested;
    private final AtomicLong finalDeadline = new AtomicLong(NO_DEADLINE);
    private final CompletableFuture<Void> finalDone = new CompletableFuture<>();
    private final AtomicBoolean warnedAfterClose = new AtomicBoolean();

    // The holder of the write right's
    private final NavigableMap<Integer, OverlaySegment> segments = new TreeMap<>();
    private @Nullable OverlaySegment active;
    private int nextSegment;
    private @Nullable OverlaySegment compacting;
    private final Set<Resident> inFlight = new LinkedHashSet<>();
    private final Cap evictCap = new Cap(EVICT_PER_TICK);
    private final Cap unloadCap = new Cap(UNLOAD_FLUSH_PER_TICK);
    private final Cap dueCap = new Cap(FLUSH_PER_TICK);

    private final LongAdder evicted = new LongAdder();
    private final LongAdder evictFailed = new LongAdder();
    private final LongAdder flushAppended = new LongAdder();
    private final LongAdder flushFsyncs = new LongAdder();
    private final AtomicLong flushLagMaxNanos = new AtomicLong();
    private final AtomicLong writePhaseMaxNanos = new AtomicLong();

    private OverlayStore(Path directory, SynxManifest manifest, SynxSettings settings, OverlayOptions options,
                         OverlayTuning tuning) {
        this.directory = directory;
        this.settings = settings;
        this.options = options;
        this.tuning = tuning;
        // Segment ④ is always empty in the overlay, whatever the settings say about region files
        this.header = new SynxHeader(SynxFormat.FORMAT_VERSION, SynxFormat.FLAG_OVERLAY, settings.protocolVersion(),
                settings.compressionThreshold(), settings.compressionLevel(), settings.minY(), settings.height(), 0, 0,
                settings.registryDigest(), manifest.contentHash(), new byte[32], new byte[32],
                settings.lightEngineVersion(), settings.bakerVersion());
        this.encoders = new ThreadPoolExecutor(tuning.encodeThreads(), tuning.encodeThreads(), 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), Thread.ofPlatform().daemon().name("synaxis-overlay-encode-", 0).factory());
        this.encoders.allowCoreThreadTimeOut(true);
    }

    /**
     * Opens the overlay of {@code manifest}'s release under {@code overlayRoot}, the shard's
     * {@code overlay/}, before the port is bound: deletes the directories of every other
     * {@code build_id} (F1), makes {@code <build_id>/} and rebuilds the index from its segment files,
     * the record of the highest {@code generation} of each chunk. The first record appended later
     * starts a new segment.
     *
     * @param settings what segment ① is framed with; the process's protocol, registries, compression
     *                 and world, which the segment headers must match
     * @throws IOException   if the directory cannot be made or read; the shard refuses to start
     * @throws SynxException if a segment header does not match the process or the release (§14)
     */
    public static OverlayStore open(Path overlayRoot, SynxManifest manifest, SynxSettings settings,
                                    OverlayOptions options) throws IOException, SynxException {
        return open(overlayRoot, manifest, settings, options, OverlayTuning.defaults());
    }

    static OverlayStore open(Path overlayRoot, SynxManifest manifest, SynxSettings settings, OverlayOptions options,
                             OverlayTuning tuning) throws IOException, SynxException {
        Files.createDirectories(overlayRoot);
        final List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(overlayRoot)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                        && !entry.getFileName().toString().equals(manifest.buildId())) {
                    stale.add(entry);
                }
            }
        }
        for (Path directory : stale) {
            deleteTree(directory);
            LOGGER.info("Overlay of {} deleted: the shard runs {} now", directory.getFileName(), manifest.buildId());
        }
        final OverlayStore store = new OverlayStore(Files.createDirectories(overlayRoot.resolve(manifest.buildId())),
                manifest, settings, options, tuning);
        store.load(new SynxExpectations(settings.protocolVersion(), settings.registryDigest(),
                settings.compressionThreshold(), settings.minY(), settings.height(), settings.lightEngineVersion()),
                manifest.contentHash());
        return store;
    }

    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private void load(SynxExpectations expectations, byte[] contentHash) throws IOException, SynxException {
        final NavigableMap<Integer, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                final Matcher matcher = SEGMENT_NAME.matcher(entry.getFileName().toString());
                if (matcher.matches()) files.put(Integer.parseInt(matcher.group(1)), entry);
            }
        }
        for (Map.Entry<Integer, Path> file : files.entrySet()) {
            final OverlaySegment segment = OverlaySegment.scan(file.getValue(), file.getKey(), expectations,
                    contentHash, this::found);
            if (segment == null) {
                // The first fsync of a segment makes its header durable too: no record of this one was confirmed
                try {
                    Files.delete(file.getValue());
                    LOGGER.warn("Overlay segment {} has no header, a crash while it was made: deleted",
                            file.getValue().getFileName());
                } catch (IOException e) {
                    LOGGER.warn("Overlay segment {} has no header, a crash while it was made, and could not be deleted: {}",
                            file.getValue().getFileName(), e.toString());
                }
                continue;
            }
            segments.put(file.getKey(), segment);
        }
        nextSegment = files.isEmpty() ? 0 : files.lastKey() + 1;
        for (Indexed indexed : index.values()) indexed.segment().live++;
        LOGGER.info("Overlay store {}: records of {} chunks in {} segments", directory, index.size(), segments.size());
    }

    /** A record found on start-up; of two with the same {@code generation} the later one wins, a compaction copy. */
    private void found(OverlaySegment segment, long offset, long generation, SynxSlot.Present record) {
        final Indexed found = new Indexed(new OverlayHit(record, generation, segment.number, offset), segment);
        index.merge(CoordConversion.chunkIndex(record.chunkX(), record.chunkZ()), found,
                (known, next) -> next.hit().generation() >= known.hit().generation() ? next : known);
    }

    // ---- OverlayIndex

    /**
     * The pending table first, then the index; from the threads chunks load on. Lock free, but for a
     * chunk an unload is encoding into the pending table right now, whose encoding it waits for: the
     * index only has older content of it.
     */
    @Override
    public @Nullable OverlayHit find(int chunkX, int chunkZ) {
        final long key = CoordConversion.chunkIndex(chunkX, chunkZ);
        CompletableFuture<@Nullable Unloaded> waiting;
        while ((waiting = pending.get(key)) != null) {
            final Unloaded unloaded = waiting.join();
            if (unloaded != null) return unloaded.hit();
            // Not encoded: unloaded() put back the entry it replaced, if any, before completing
        }
        final Indexed indexed = index.get(key);
        return indexed != null ? indexed.hit() : null;
    }

    // ---- OverlayTracker

    @Override
    public void materialized(OverlayChunk chunk) {
        track(chunk, false);
    }

    @Override
    public void modified(OverlayChunk chunk) {
        track(chunk, true);
    }

    private void track(OverlayChunk chunk, boolean changed) {
        if (writeRight.get() == CLOSED) {
            if (changed) changedAfterClose(chunk);
            return;
        }
        while (true) {
            final Resident resident = residents.computeIfAbsent(chunk, _ -> new Resident(chunk, key(chunk)));
            synchronized (resident) {
                // Evicted or unloaded while this thread got hold of it: a new one
                if (resident.removed) continue;
                if (changed || resident.lruKey == 0) {
                    if (resident.lruKey != 0) lru.remove(resident.lruKey);
                    resident.lruKey = sequence.incrementAndGet();
                    lru.put(resident.lruKey, resident);
                }
                if (changed && resident.dirtyKey == 0) {
                    resident.dirtySince = tuning.clock().getAsLong();
                    resident.dirtyKey = sequence.incrementAndGet();
                    dirtyQueue.put(resident.dirtyKey, resident);
                }
                return;
            }
        }
    }

    @Override
    public void unloadRequested(OverlayChunk chunk) {
        final Resident resident = residents.get(chunk);
        if (resident == null) return;
        synchronized (resident) {
            if (!resident.removed && resident.dirtyKey != 0) unloadQueue.add(resident);
        }
    }

    /**
     * Leaves the residents; a chunk with changes the write phase has not written is encoded here, on
     * the unloading thread, into the pending table, which {@link #find} answers from until the next
     * write phase has written it. Its entry goes in before the encoding, so that a load of the chunk
     * meanwhile waits for it. The caller holds the instance monitor, and this takes the chunk's
     * write-lock, the order of {@code UNSAFE_setBlock}.
     */
    @Override
    public void unloaded(OverlayChunk chunk) {
        final Resident resident = residents.get(chunk);
        if (resident != null) {
            synchronized (resident) {
                remove(resident);
            }
        }
        if (!chunk.dirty()) return;
        if (writeRight.get() == CLOSED) {
            changedAfterClose(chunk);
            return;
        }
        final long key = key(chunk);
        final CompletableFuture<@Nullable Unloaded> entry = new CompletableFuture<>();
        final CompletableFuture<@Nullable Unloaded> replaced = pending.put(key, entry);
        Unloaded unloaded = null;
        try {
            final OverlayEncoding encoding = chunk.encodeOverlay(settings, System.nanoTime() + UNLOAD_ENCODE_NANOS);
            if (encoding == null) {
                LOGGER.error("Chunk {},{} was unloaded with changes, but its write-lock was not had in time: they are lost",
                        chunk.chunkX(), chunk.chunkZ());
            } else {
                final byte[] record = record(chunk, encoding);
                unloaded = new Unloaded(new OverlayHit(present(chunk, record), encoding.generation(), -1,
                        pendingSequence.incrementAndGet()), record);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Chunk {},{} was unloaded with changes that do not encode: they are lost", chunk.chunkX(),
                    chunk.chunkZ(), e);
        } finally {
            if (unloaded == null) {
                // The content the table had stays the latest there is
                if (replaced != null) pending.replace(key, entry, replaced);
                else pending.remove(key, entry);
            }
            entry.complete(unloaded);
        }
        // Closed while this encoded: the final flush left the entry out
        if (unloaded != null && writeRight.get() == CLOSED && pending.get(key) == entry) changedAfterClose(chunk);
    }

    private void changedAfterClose(OverlayChunk chunk) {
        if (warnedAfterClose.compareAndSet(false, true)) {
            LOGGER.warn("Chunk {},{} changed after the overlay store's final flush: the change is not written and is lost "
                    + "when the process exits", chunk.chunkX(), chunk.chunkZ());
        }
    }

    // ---- The write phase

    /**
     * The write phase, once a tick on the tick scheduling thread after every tick thread is done with
     * the tick; it is the final flush instead once one was requested. Skipped while the shutdown
     * thread holds the write right, and after the final flush.
     */
    public void tickEnd() {
        if (!writeRight.compareAndSet(IDLE, TICK)) return;
        final long start = System.nanoTime();
        try {
            // Read once: a request made while the phase runs is the next phase's
            if (finalRequested) finalFlushNow("tick");
            else writePhase(start);
        } finally {
            writePhaseMaxNanos.accumulateAndGet(System.nanoTime() - start, Math::max);
            release(TICK);
        }
    }

    private void release(int holder) {
        writeRight.compareAndSet(holder, IDLE);
        synchronized (rightReleased) {
            rightReleased.notifyAll();
        }
    }

    private void writePhase(long start) {
        final long deadline = start + PHASE_NANOS;
        final Set<Resident> taken = new HashSet<>();
        selectEvictions(taken, deadline);
        selectUnloads(taken, deadline);
        selectDue(taken, deadline);
        awaitEncodings(deadline);
        final List<Item> batch = new ArrayList<>();
        takePending(batch);
        harvest(batch, false, false);
        selectCopies(batch);
        if (!batch.isEmpty() && write(batch) != null) {
            final OverlaySegment current = active;
            if (current != null && current.records() >= tuning.segmentRecords()) {
                current.seal();
                active = null;
            }
            unlinkEmptySegments();
        }
    }

    /** The pending entries encoded by now, first in the batch; one still encoding waits for the next batch. */
    private void takePending(List<Item> batch) {
        pending.forEach((key, entry) -> {
            final Unloaded unloaded = entry.getNow(null);
            if (unloaded != null) batch.add(new PendingItem(key, entry, unloaded));
        });
    }

    /**
     * {@code overlay_resident − overlay_cap}, at most 64 with those in flight, the least recently changed
     * first, but none a light recomputation waits to install into (M3-D).
     */
    private void selectEvictions(Set<Resident> taken, long deadline) {
        int count = Math.min(evictCap.left(), residents.size() - options.cap() - evictCap.inFlight);
        for (Iterator<Resident> it = lru.values().iterator(); count > 0 && it.hasNext(); ) {
            final Resident resident = it.next();
            if (resident.inFlight != null || resident.chunk.relightPending() || !taken.add(resident)) continue;
            count--;
            final OverlayChunk chunk = resident.chunk;
            final long generation = chunk.generation();
            final Indexed indexed = index.get(resident.key);
            if (!chunk.dirty() && indexed != null && indexed.hit().generation() == generation) {
                // Clean: back to handle mode on the record it was written to, nothing to append
                if (chunk.written(generation, indexed.hit(), true)) afterWritten(resident, true, 0);
            } else {
                submit(resident, evictCap, deadline);
            }
        }
    }

    /** The dirty chunks the automatic unload waits for, at most 256 with those in flight. */
    private void selectUnloads(Set<Resident> taken, long deadline) {
        for (Iterator<Resident> it = unloadQueue.iterator(); unloadCap.left() > 0 && it.hasNext(); ) {
            final Resident resident = it.next();
            if (resident.inFlight != null || !resident.chunk.dirty() || !taken.add(resident)) continue;
            submit(resident, unloadCap, deadline);
        }
    }

    /**
     * Dirty for {@code flush_interval_s} less {@link #DUE_LEAD_NANOS} or longer, at most 64 with
     * those in flight, the longest first.
     */
    private void selectDue(Set<Resident> taken, long deadline) {
        final long now = tuning.clock().getAsLong();
        final long interval = TimeUnit.SECONDS.toNanos(options.flushIntervalSeconds()) - DUE_LEAD_NANOS;
        for (Iterator<Resident> it = dirtyQueue.values().iterator(); dueCap.left() > 0 && it.hasNext(); ) {
            final Resident resident = it.next();
            if (now - resident.dirtySince < interval) break;
            if (resident.inFlight != null || !taken.add(resident)) continue;
            submit(resident, dueCap, deadline);
        }
    }

    /** {@code cap} is null for the final flush, which has none. */
    private void submit(Resident resident, @Nullable Cap cap, long deadline) {
        final OverlayChunk chunk = resident.chunk;
        resident.inFlight = encoders.submit(() -> encode(chunk, deadline));
        resident.cap = cap;
        if (cap != null) cap.inFlight++;
        inFlight.add(resident);
    }

    /** On an encode thread; null when the chunk's write-lock was not had in time, or the final flush's deadline passed before it started. */
    private @Nullable Encoded encode(OverlayChunk chunk, long deadline) {
        final long stop = finalDeadline.get();
        if (stop != NO_DEADLINE && System.nanoTime() - stop >= 0) return null;
        final OverlayEncoding encoding = chunk.encodeOverlay(settings, deadline);
        if (encoding == null) return null;
        final byte[] record = record(chunk, encoding);
        present(chunk, record);
        return new Encoded(encoding.generation(), record);
    }

    private byte[] record(OverlayChunk chunk, OverlayEncoding encoding) {
        final SynxChunkRecord record = encoding.record();
        if (record.chunkX() != chunk.chunkX() || record.chunkZ() != chunk.chunkZ()) {
            throw new IllegalStateException("chunk " + chunk.chunkX() + "," + chunk.chunkZ() + " encoded the record of "
                    + record.chunkX() + "," + record.chunkZ());
        }
        return record.encode(settings);
    }

    /** The record as the overlay reads it, which also checks it is one: segment ④ empty. */
    private static SynxSlot.Present present(OverlayChunk chunk, byte[] record) {
        final SynxSlot slot = SynxSlot.parse(chunk.chunkX(), chunk.chunkZ(), MemorySegment.ofArray(record), false);
        if (slot instanceof SynxSlot.Present present) return present;
        final SynxSlot.Failed failed = (SynxSlot.Failed) slot;
        throw new IllegalStateException("chunk " + chunk.chunkX() + "," + chunk.chunkZ() + " encoded no overlay record: "
                + failed.fault() + " " + failed.detail());
    }

    /** Waits for the encodings in flight until {@code deadline}, the oldest first. */
    private void awaitEncodings(long deadline) {
        for (Resident resident : inFlight) {
            final long left = deadline - System.nanoTime();
            if (left <= 0) return;
            try {
                Objects.requireNonNull(resident.inFlight).get(left, TimeUnit.NANOSECONDS);
            } catch (TimeoutException _) {
                return;
            } catch (ExecutionException | CancellationException _) {
                // harvest reports it
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Takes the finished encodings into {@code batch}, in the order they were submitted: those still
     * at the chunk's {@code generation}, or with {@code staleToo} every one, content of a real
     * generation newer than the index's. The chunks of the others stay dirty and are encoded again.
     * Evictions demote, but not in the final flush.
     */
    private void harvest(List<Item> batch, boolean finalFlush, boolean staleToo) {
        for (Iterator<Resident> it = inFlight.iterator(); it.hasNext(); ) {
            final Resident resident = it.next();
            final Future<Encoded> future = Objects.requireNonNull(resident.inFlight);
            if (!future.isDone()) continue;
            it.remove();
            resident.inFlight = null;
            final Cap cap = resident.cap;
            if (cap != null) cap.inFlight--;
            final Encoded encoded = result(resident.chunk, future);
            if (encoded == null) continue;
            synchronized (resident) {
                // Unloaded since: the pending table has its latest content
                if (resident.removed) continue;
            }
            if (!staleToo && encoded.generation() != resident.chunk.generation()) continue;
            batch.add(new ChunkItem(resident, encoded, cap == evictCap && !finalFlush));
        }
    }

    private static @Nullable Encoded result(OverlayChunk chunk, Future<Encoded> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            LOGGER.error("Chunk {},{} does not encode into an overlay record, it stays dirty", chunk.chunkX(),
                    chunk.chunkZ(), e.getCause());
            return null;
        } catch (CancellationException _) {
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Compaction: the records still in the index of a sealed segment more than half of whose
     * records were written over, copied as they are, at most 64 a tick.
     */
    private void selectCopies(List<Item> batch) {
        OverlaySegment segment = compacting;
        if (segment == null) {
            for (OverlaySegment candidate : segments.values()) {
                if (candidate != active && candidate.records() > 0
                        && (candidate.records() - candidate.live) * 2 > candidate.records()) {
                    segment = candidate;
                    segment.compacted = 0;
                    compacting = segment;
                    break;
                }
            }
            if (segment == null) return;
        }
        int copies = 0;
        while (copies < COPY_PER_TICK && segment.compacted < segment.records()) {
            final int record = segment.compacted++;
            final long key = segment.key(record);
            final Indexed indexed = index.get(key);
            if (indexed != null && indexed.segment() == segment && indexed.hit().offset() == segment.offset(record)) {
                batch.add(new CopyItem(key, indexed));
                copies++;
            }
        }
        if (segment.compacted == segment.records()) compacting = null;
    }

    /**
     * Appends {@code batch} in order and forces every segment it went into, the group commit; then
     * the index names the new records, the pending entries written leave the table, and the chunks
     * hear about their records.
     *
     * @return null if the batch failed: then nothing of it is indexed, the chunks stay dirty, and the
     *         segments it went into take no more records
     */
    private @Nullable Commit write(List<Item> batch) {
        final List<Appended> appended = new ArrayList<>(batch.size());
        final Set<OverlaySegment> touched = new LinkedHashSet<>();
        long bytes = 0;
        final long fsyncNanos;
        try {
            for (Item item : batch) {
                final Appended record = append(item, touched);
                appended.add(record);
                bytes += OverlaySegment.RECORD_HEADER_BYTES + record.segment().length(record.offset());
            }
            final long start = System.nanoTime();
            for (OverlaySegment segment : touched) segment.force(tuning.disk());
            fsyncNanos = System.nanoTime() - start;
        } catch (IOException e) {
            evictFailed.increment();
            LOGGER.error("The overlay write phase failed in {}: none of its {} records is indexed, the chunks stay dirty "
                    + "and are written again", directory, batch.size(), e);
            abandon(touched);
            return null;
        }
        flushFsyncs.add(touched.size());
        flushAppended.add(appended.size());
        final long writtenAt = tuning.clock().getAsLong();
        final List<OverlayHit> hits = new ArrayList<>(appended.size());
        for (Appended record : appended) hits.add(index(record));
        for (Appended record : appended) {
            if (record.item() instanceof PendingItem written) pending.remove(written.key(), written.entry());
        }
        for (int i = 0; i < appended.size(); i++) {
            if (appended.get(i).item() instanceof ChunkItem written) {
                final Resident resident = written.resident();
                if (resident.chunk.written(written.encoded().generation(), hits.get(i), written.demote())) {
                    afterWritten(resident, written.demote(), writtenAt);
                }
            }
        }
        for (OverlaySegment segment : touched) {
            if (segment != active) segment.seal();
        }
        return new Commit(bytes, fsyncNanos);
    }

    private Appended append(Item item, Set<OverlaySegment> touched) throws IOException {
        return switch (item) {
            case PendingItem written -> {
                final OverlayHit hit = written.unloaded().hit();
                yield appendRecord(item, hit.record().chunkX(), hit.record().chunkZ(), hit.generation(),
                        MemorySegment.ofArray(written.unloaded().record()), touched);
            }
            case ChunkItem written -> appendRecord(item, written.resident().chunk.chunkX(),
                    written.resident().chunk.chunkZ(), written.encoded().generation(),
                    MemorySegment.ofArray(written.encoded().record()), touched);
            case CopyItem copy -> {
                final OverlaySegment from = copy.from().segment();
                final long offset = copy.from().hit().offset();
                final OverlaySegment segment = active(from.length(offset), touched);
                yield new Appended(item, segment, segment.copy(tuning.disk(), from, offset), copy.from().hit().generation());
            }
        };
    }

    private Appended appendRecord(Item item, int chunkX, int chunkZ, long generation, MemorySegment record,
                                  Set<OverlaySegment> touched) throws IOException {
        final OverlaySegment segment = active(record.byteSize(), touched);
        return new Appended(item, segment, segment.append(tuning.disk(), chunkX, chunkZ, generation, record), generation);
    }

    /** The segment the next record goes into, a new one when there is none or it is full; the batch forces it. */
    private OverlaySegment active(long length, Set<OverlaySegment> touched) throws IOException {
        OverlaySegment segment = active;
        if (segment == null || !segment.fits(length)) {
            segment = OverlaySegment.create(directory, nextSegment++, header, tuning.segmentBytes(), tuning.disk());
            segments.put(segment.number, segment);
            active = segment;
        }
        touched.add(segment);
        return segment;
    }

    /** Nothing goes behind bytes of unknown state: the segments of a failed batch are done, and gone if they hold nothing confirmed. */
    private void abandon(Set<OverlaySegment> touched) {
        for (OverlaySegment segment : touched) {
            segment.seal();
            if (segment == active) active = null;
            if (segment.records() > 0) continue;
            segments.remove(segment.number);
            try {
                segment.delete();
            } catch (IOException e) {
                LOGGER.warn("Overlay segment {} with no records could not be deleted: {}", segment.name(), e.toString());
            }
        }
    }

    /** Points the index at a record written; never back to an older generation. */
    private OverlayHit index(Appended appended) {
        final OverlaySegment segment = appended.segment();
        final OverlayHit hit = new OverlayHit(segment.present(appended.offset()), appended.generation(), segment.number,
                appended.offset());
        final Indexed next = new Indexed(hit, segment);
        final long key = CoordConversion.chunkIndex(hit.record().chunkX(), hit.record().chunkZ());
        segment.confirmed(key, appended.offset());
        if (appended.item() instanceof CopyItem copy) {
            index.computeIfPresent(key, (_, current) -> current == copy.from() ? moved(current, next) : current);
        } else {
            index.compute(key, (_, current) -> current == null || current.hit().generation() <= hit.generation()
                    ? moved(current, next) : current);
        }
        return hit;
    }

    private static Indexed moved(@Nullable Indexed from, Indexed to) {
        if (from != null) from.segment().live--;
        to.segment().live++;
        return to;
    }

    /** A chunk heard about its record: clean now unless it changed since, and out of the residents if evicted. */
    private void afterWritten(Resident resident, boolean demote, long writtenAt) {
        synchronized (resident) {
            // Changed after the encoding: still dirty
            if (resident.removed || resident.chunk.dirty()) return;
            if (resident.dirtyKey != 0 && writtenAt != 0) {
                flushLagMaxNanos.accumulateAndGet(writtenAt - resident.dirtySince, Math::max);
            }
            clean(resident);
            if (demote) {
                remove(resident);
                evicted.increment();
            }
        }
    }

    // Both with the resident's monitor held
    private void clean(Resident resident) {
        if (resident.dirtyKey != 0) {
            dirtyQueue.remove(resident.dirtyKey);
            resident.dirtyKey = 0;
        }
        unloadQueue.remove(resident);
    }

    private void remove(Resident resident) {
        if (resident.removed) return;
        resident.removed = true;
        residents.remove(resident.chunk, resident);
        if (resident.lruKey != 0) lru.remove(resident.lruKey);
        clean(resident);
    }

    /** Unlinks the sealed segments the index names no record of, which compaction emptied or newer records overwrote. */
    private void unlinkEmptySegments() {
        for (Iterator<OverlaySegment> it = segments.values().iterator(); it.hasNext(); ) {
            final OverlaySegment segment = it.next();
            if (segment == active || segment.live > 0) continue;
            if (segment == compacting) compacting = null;
            try {
                segment.delete();
                it.remove();
            } catch (IOException e) {
                LOGGER.warn("Overlay segment {} could not be deleted: {}", segment.name(), e.toString());
            }
        }
    }

    // ---- The final flush

    /**
     * The shutdown step of the overlay ({@code docs/DESIGN-PROD.md} API-PROD-007, shaped as
     * {@code ShutdownSequence.Step#run}): writes every dirty chunk once more, then closes the store.
     * Capped at min(2 s, {@code remaining}); a second call takes the earlier deadline. At the
     * deadline the returned future completes whether or not the writer is done; a writer past it
     * appends what is encoded and starts no more encodings.
     *
     * @param tickAlive true: only asks the next write phase to do it and returns at once. False: the
     *                  watchdog judged the tick dead, and the calling thread does it, unless the write
     *                  phase has begun it already, which it then waits for until the deadline
     * @return completes once the final flush is over, done or not
     */
    public CompletableFuture<?> finalFlush(Duration remaining, boolean tickAlive) {
        final long budget = Math.min(FINAL_CAP_NANOS, Math.max(0, remaining.toNanos()));
        final long deadline = System.nanoTime() + budget;
        finalDeadline.accumulateAndGet(deadline, (current, next) -> current == NO_DEADLINE || next - current < 0 ? next : current);
        finalRequested = true;
        var _ = CompletableFuture.runAsync(() -> timedOut(budget),
                CompletableFuture.delayedExecutor(budget, TimeUnit.NANOSECONDS));
        if (!tickAlive) takeOver();
        return finalDone;
    }

    /** The shutdown thread: the final flush if the write right is free, otherwise wait for its holder. */
    private void takeOver() {
        while (true) {
            final int state = writeRight.get();
            if (state == CLOSED) {
                awaitFinalDone();
                return;
            }
            if (state == IDLE) {
                if (!writeRight.compareAndSet(IDLE, SHUTDOWN)) continue;
                try {
                    finalFlushNow("shutdown");
                } finally {
                    release(SHUTDOWN);
                }
                return;
            }
            // The write phase runs: judged dead is not stopped
            final long left = finalDeadline.get() - System.nanoTime();
            if (left <= 0) return;
            synchronized (rightReleased) {
                if (writeRight.get() != state) continue;
                try {
                    rightReleased.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void awaitFinalDone() {
        try {
            finalDone.get(Math.max(0, finalDeadline.get() - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException _) {
            // the deadline's own completion reports it
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * With the write right: every dirty chunk, no cap, encoded until the deadline, one {@code fsync},
     * closed. An encoding a write phase left in flight was made against that phase's deadline: it is
     * taken if still at its chunk's {@code generation}, and the chunk encoded again if not, or if it
     * came to null.
     */
    private void finalFlushNow(String by) {
        final long deadline = finalDeadline.get();
        final List<Item> batch = new ArrayList<>();
        takePending(batch);
        awaitEncodings(deadline);
        harvest(batch, true, false);
        final Set<Resident> taken = new HashSet<>();
        for (Item item : batch) {
            if (item instanceof ChunkItem written) taken.add(written.resident());
        }
        for (Resident resident : residents.values()) {
            if (System.nanoTime() - deadline >= 0) break;
            if (resident.inFlight == null && resident.chunk.dirty() && !taken.contains(resident)) {
                submit(resident, null, deadline);
            }
        }
        awaitEncodings(deadline);
        harvest(batch, true, true);
        final Commit written = batch.isEmpty() ? new Commit(0, 0) : write(batch);
        writeRight.set(CLOSED);
        encoders.shutdownNow();
        final OverlaySegment current = active;
        if (current != null) current.seal();
        active = null;
        final long left = dirtyLeft();
        final int chunks = written == null ? 0 : batch.size();
        final long bytes = written == null ? 0 : written.bytes();
        final long fsyncMs = written == null ? 0 : TimeUnit.NANOSECONDS.toMillis(written.fsyncNanos());
        final String line = "Overlay final flush: chunks={} bytes={} fsync_ms={} left={} by={}";
        if (left > 0) LOGGER.error(line, chunks, bytes, fsyncMs, left, by);
        else LOGGER.info(line, chunks, bytes, fsyncMs, left, by);
        finalDone.complete(null);
    }

    private long dirtyLeft() {
        long left = pending.size();
        for (Resident resident : residents.values()) {
            if (resident.chunk.dirty()) left++;
        }
        return left;
    }

    private void timedOut(long budget) {
        if (finalDone.isDone()) return;
        LOGGER.error("Overlay final flush did not finish within {} ms: {} dirty chunks left",
                TimeUnit.NANOSECONDS.toMillis(budget), dirtyLeft());
        finalDone.complete(null);
    }

    /**
     * The counters of {@code EVT:chunk_stream_stats}; reading resets {@code flushLagSMax} and
     * {@code writePhaseMsMax}, so only the shard host's 60 s task calls it.
     */
    public OverlayStats stats() {
        return new OverlayStats(residents.size(), evicted.sum(), evictFailed.sum(), flushAppended.sum(),
                flushFsyncs.sum(), (double) flushLagMaxNanos.getAndSet(0) / 1e9,
                (double) writePhaseMaxNanos.getAndSet(0) / 1e6, index.size());
    }

    private static long key(OverlayChunk chunk) {
        return CoordConversion.chunkIndex(chunk.chunkX(), chunk.chunkZ());
    }
}
