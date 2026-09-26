package io.github.bearisbug.synaxis.synx;

import org.jetbrains.annotations.Nullable;

import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@link OverlayChunk} of the overlay store unit tests ({@code docs/DESIGN-M3.md} §19): its
 * content is its coordinates and its {@code generation}, its write-lock a {@link ReentrantLock} it
 * holds the way {@code SynxChunk} holds its own, and its encoding can be slowed down, refused or made
 * to throw.
 */
final class FakeChunk implements OverlayChunk {
    private static final long LOCK_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    enum Encoding { NORMAL, NULL, THROW }

    record Written(long generation, OverlayHit hit, boolean demote) {
    }

    private final OverlayTracker tracker;
    private final int chunkX;
    private final int chunkZ;
    final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong generation;
    private volatile boolean dirty;
    private volatile boolean overlay;

    volatile long encodeNanos;
    volatile Encoding encoding = Encoding.NORMAL;

    final AtomicInteger encodes = new AtomicInteger();
    final AtomicInteger maxRunning = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    final Queue<Long> encodeStarts = new ConcurrentLinkedQueue<>();
    final Queue<String> encodeThreads = new ConcurrentLinkedQueue<>();
    final List<Written> written = new CopyOnWriteArrayList<>();

    FakeChunk(OverlayTracker tracker, int chunkX, int chunkZ, long generation) {
        this.tracker = tracker;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.generation = new AtomicLong(generation);
    }

    /** One change: materializes first in handle mode, as {@code SynxChunk#setBlock} does. */
    void change() {
        lock.lock();
        try {
            if (!overlay) {
                overlay = true;
                tracker.materialized(this);
            }
            generation.incrementAndGet();
            dirty = true;
            tracker.modified(this);
        } finally {
            lock.unlock();
        }
    }

    boolean overlay() {
        return overlay;
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public long generation() {
        return generation.get();
    }

    @Override
    public boolean dirty() {
        return dirty;
    }

    @Override
    public @Nullable OverlayEncoding encodeOverlay(SynxSettings settings, long deadlineNanos) {
        encodes.incrementAndGet();
        encodeStarts.add(System.nanoTime());
        encodeThreads.add(Thread.currentThread().getName());
        maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
            final long wait = Math.min(deadlineNanos - System.nanoTime(), LOCK_WAIT_NANOS);
            if (!lock.tryLock(Math.max(0, wait), TimeUnit.NANOSECONDS)) return null;
            try {
                switch (encoding) {
                    case NULL -> {
                        return null;
                    }
                    case THROW -> throw new IllegalStateException("chunk " + chunkX + "," + chunkZ + " does not encode");
                    case NORMAL -> {
                    }
                }
                if (encodeNanos > 0) Thread.sleep(Duration.ofNanos(encodeNanos));
                final long current = generation.get();
                return new OverlayEncoding(current, record(chunkX, chunkZ, current));
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            running.decrementAndGet();
        }
    }

    @Override
    public void written(long generation, OverlayHit hit, boolean demote) {
        written.add(new Written(generation, hit, demote));
        lock.lock();
        try {
            if (this.generation.get() != generation) return;
            dirty = false;
            if (demote) overlay = false;
        } finally {
            lock.unlock();
        }
    }

    /** The content of a chunk at a generation: segment ① holds the coordinates and the generation. */
    static SynxChunkRecord record(int chunkX, int chunkZ, long generation) {
        final byte[] packet = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(chunkX).putInt(chunkZ).putLong(generation).array();
        return new SynxChunkRecord(chunkX, chunkZ, packet, new byte[SynxFormat.NAVIGATION_BYTES], List.of(), List.of(),
                new byte[0]);
    }

    /** The generation a record holds, after checking it is the record of {@code (chunkX, chunkZ)}. */
    static long generationOf(OverlayHit hit, int chunkX, int chunkZ) {
        final SynxSlot.Present record = hit.record();
        if (record.chunkX() != chunkX || record.chunkZ() != chunkZ
                || record.packet().get(INT, 0) != chunkX || record.packet().get(INT, 4) != chunkZ) {
            throw new AssertionError("the record of " + record.chunkX() + "," + record.chunkZ() + " where "
                    + chunkX + "," + chunkZ + " was looked up");
        }
        return record.packet().get(LONG, 8);
    }
}
