package io.github.bearisbug.synaxis.synx;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** What the overlay store tests open a store with. */
final class OverlayFixture {
    static final String MAP_ID = "overlay-test";
    static final byte[] REGISTRY_DIGEST = SynxDigests.sha256(new byte[]{7});
    static final byte[] CONTENT_HASH = SynxDigests.sha256(new byte[]{9});
    /**
     * How long the write phase of the tests waits for its encodings: longer than any of them takes, so
     * that a tick writes everything it picked however slow the machine. The tests of what a phase over
     * the 20 ms of a shard leaves to the next ticks use {@link #shardPhase}.
     */
    static final long TEST_PHASE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private OverlayFixture() {
    }

    static SynxSettings settings() {
        return settings(776, REGISTRY_DIGEST);
    }

    static SynxSettings settings(int protocolVersion, byte[] registryDigest) {
        return new SynxSettings(protocolVersion, 256, -1, 0, 256, registryDigest, 1, 1, false);
    }

    /** A release of {@link #MAP_ID} for {@code protocolVersion}, so another protocol is another {@code build_id}. */
    static SynxManifest manifest(int protocolVersion) {
        return manifest(SynxManifest.buildId(MAP_ID, CONTENT_HASH, protocolVersion), CONTENT_HASH, protocolVersion);
    }

    static SynxManifest manifest(String buildId, byte[] contentHash, int protocolVersion) {
        return new SynxManifest(MAP_ID, buildId, contentHash, "26.2", protocolVersion, REGISTRY_DIGEST, 1, 1,
                new SynxManifest.World(0, 256), new SynxManifest.Bounds(-64, -64, 63, 63), List.of(),
                new SynxManifest.Source("generator", null, null),
                new SynxManifest.Bake("2026-09-27T00:00:00Z", 1, 1, 1, "25"), new SynxManifest.Verify(0, 0, null));
    }

    /** 1 MiB segments: the sparse files of the tests stay small. */
    static OverlayTuning tuning(int encodeThreads, Clock clock, CountingDisk disk) {
        return tuning(encodeThreads, OverlayTuning.SEGMENT_RECORDS, clock, disk);
    }

    static OverlayTuning tuning(int encodeThreads, int segmentRecords, Clock clock, CountingDisk disk) {
        return new OverlayTuning(encodeThreads, 1L << 20, segmentRecords, TEST_PHASE_NANOS, clock, disk);
    }

    /** {@link #tuning} with the 20 ms write phase of a shard. */
    static OverlayTuning shardPhase(int encodeThreads, Clock clock, CountingDisk disk) {
        return new OverlayTuning(encodeThreads, 1L << 20, OverlayTuning.SEGMENT_RECORDS, OverlayTuning.PHASE_NANOS,
                clock, disk);
    }

    static Path segment(Path root, SynxManifest manifest, int number) {
        return root.resolve(manifest.buildId()).resolve("seg." + number + ".synx");
    }

    /** {@code count} chunks in a row along x from {@code firstX}, all at generation 0. */
    static List<FakeChunk> chunks(OverlayTracker tracker, int firstX, int count) {
        final List<FakeChunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) chunks.add(new FakeChunk(tracker, firstX + i, 0, 0));
        return chunks;
    }

    /** A clock the test moves by hand. */
    static final class Clock implements LongSupplier {
        private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long getAsLong() {
            return nanos.get();
        }
    }

    /** The segment files, counted, with a hook in front of every {@code fsync} and failures on demand. */
    static final class CountingDisk implements OverlayTuning.Disk {
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger forces = new AtomicInteger();
        final Queue<String> writeThreads = new ConcurrentLinkedQueue<>();
        final Queue<String> forceThreads = new ConcurrentLinkedQueue<>();
        volatile @Nullable Runnable onForce;
        volatile @Nullable IOException failWrite;
        volatile @Nullable IOException failForce;

        @Override
        public void write(FileChannel channel, ByteBuffer bytes, long position) throws IOException {
            final IOException failure = failWrite;
            if (failure != null) throw failure;
            writes.incrementAndGet();
            writeThreads.add(Thread.currentThread().getName());
            OverlayTuning.FILE.write(channel, bytes, position);
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            final Runnable hook = onForce;
            if (hook != null) hook.run();
            final IOException failure = failForce;
            if (failure != null) throw failure;
            forces.incrementAndGet();
            forceThreads.add(Thread.currentThread().getName());
            OverlayTuning.FILE.force(channel);
        }
    }
}
