package io.github.bearisbug.synaxis.synx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.LongSupplier;

/**
 * What the tests of the overlay store change about it; a shard runs on {@link #defaults()}
 * ({@code docs/DESIGN-M3.md} API-M3-007).
 *
 * @param encodeThreads  the platform threads of the encode pool
 * @param segmentBytes   the size of a segment file, and of its mapping
 * @param segmentRecords records after which the active segment is sealed
 * @param clock          the {@link System#nanoTime()} the flush interval and the flush lag are measured on; the
 *                       deadlines of the write phase and of the final flush are always on {@link System#nanoTime()}
 * @param disk           the writes and the {@code fsync} of the segment files
 */
record OverlayTuning(int encodeThreads, long segmentBytes, int segmentRecords, LongSupplier clock, Disk disk) {
    /** 256 MiB, the segment size of API-M3-007. */
    static final long SEGMENT_BYTES = 256L << 20;
    static final int SEGMENT_RECORDS = 1_024;

    /** Where the store's bytes reach the file. */
    interface Disk {
        void write(FileChannel channel, ByteBuffer bytes, long position) throws IOException;

        void force(FileChannel channel) throws IOException;
    }

    /** The file itself: positional writes and {@code force(false)}. */
    static final Disk FILE = new Disk() {
        @Override
        public void write(FileChannel channel, ByteBuffer bytes, long position) throws IOException {
            while (bytes.hasRemaining()) position += channel.write(bytes, position);
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            channel.force(false);
        }
    };

    /** {@code max(1, cores / 4)} encode threads, fixed in M3-C (API-M3-007). */
    static OverlayTuning defaults() {
        return new OverlayTuning(Math.max(1, Runtime.getRuntime().availableProcessors() / 4), SEGMENT_BYTES,
                SEGMENT_RECORDS, System::nanoTime, FILE);
    }
}
