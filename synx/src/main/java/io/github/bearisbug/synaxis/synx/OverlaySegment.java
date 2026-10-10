package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * One segment file of the overlay store ({@code docs/DESIGN-M3.md} API-M3-007): the 256 byte header
 * of API-M3-001 with the {@code overlay} flag and without segment ④, then records one after the
 * other, each a 24 byte record header {@code {chunk_x i32, chunk_z i32, generation u64, length u32,
 * crc32c u32}} and a chunk record, starting on an 8 byte boundary. {@code crc32c} covers the first 20
 * bytes of the record header and the record.
 * <p>
 * The file is made at its full size and mapped once into {@code Arena.ofAuto()}: records are written
 * through the channel at their position and read through the mapping, which lives as long as a
 * record read from it, after an unlink too. Nothing written is ever written again. Everything but
 * {@link #present} belongs to the holder of the store's write right.
 */
final class OverlaySegment {
    static final int RECORD_HEADER_BYTES = 24;
    private static final int CRC_COVERED_HEADER_BYTES = 20;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final Logger LOGGER = LoggerFactory.getLogger(OverlayStore.class);

    /** What a store being opened learns from each record found. */
    @FunctionalInterface
    interface Sink {
        void record(OverlaySegment segment, long offset, long generation, SynxSlot.Present record);
    }

    final int number;
    private final Path path;
    private final MemorySegment mapping;
    // Open while the segment takes records
    private @Nullable FileChannel channel;
    private long position;
    // The records an fsync confirmed, or the start-up found, in file order
    private long[] keys = new long[64];
    private long[] offsets = new long[64];
    private int records;
    /** The index entries naming a record of this segment. */
    int live;
    /** The records compaction has looked at. */
    int compacted;

    private OverlaySegment(int number, Path path, MemorySegment mapping, @Nullable FileChannel channel, long position) {
        this.number = number;
        this.path = path;
        this.mapping = mapping;
        this.channel = channel;
        this.position = position;
    }

    static String fileName(int number) {
        return "seg." + number + ".synx";
    }

    /**
     * Makes segment {@code number} at {@code size} bytes, a sparse file, with its header written and
     * not yet forced; 0600 where the file system has POSIX permissions (M3-S). A file that fails halfway
     * is deleted.
     */
    static OverlaySegment create(Path directory, int number, SynxHeader header, long size, OverlayTuning.Disk disk)
            throws IOException {
        final Path path = directory.resolve(fileName(number));
        final FileAttribute<?>[] attributes = OverlayStore.posix(path)
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        final FileChannel channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                StandardOpenOption.WRITE), attributes);
        try {
            channel.write(ByteBuffer.allocate(1), size - 1);
            disk.write(channel, ByteBuffer.wrap(header.encode()), 0);
            final MemorySegment mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, Arena.ofAuto());
            return new OverlaySegment(number, path, mapping, channel, SynxFormat.HEADER_BYTES);
        } catch (IOException | RuntimeException e) {
            channel.close();
            Files.deleteIfExists(path);
            throw e;
        }
    }

    /**
     * Maps segment {@code number} of a store being opened and hands its records to {@code sink} in
     * file order. The records end at the first record header of a length below the segment length
     * table, the zeros after the last record; a record that runs past the file or whose
     * {@code crc32c} does not match, a record a crash cut short, ends them too, with an ERROR.
     *
     * @return the segment, sealed; null for a file shorter than a header or with a header of zeros,
     *         which a crash left while it was made
     * @throws SynxException if the header is not an overlay header of this process and release
     */
    static @Nullable OverlaySegment scan(Path path, int number, SynxExpectations expectations, byte[] contentHash,
                                         Sink sink) throws IOException, SynxException {
        final String name = path.getFileName().toString();
        final MemorySegment mapping;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.ofAuto());
        }
        if (mapping.byteSize() < SynxFormat.HEADER_BYTES
                || mapping.asSlice(0, SynxFormat.HEADER_BYTES).mismatch(MemorySegment.ofArray(new byte[SynxFormat.HEADER_BYTES])) == -1) {
            return null;
        }
        final SynxHeader header = SynxHeader.decode(mapping, name);
        if ((header.flags() & SynxFormat.FLAG_OVERLAY) == 0 || header.hasColumns()) {
            throw new SynxException(SynxError.MAGIC, name, "flags " + header.flags()
                    + ", an overlay segment has the overlay flag and no segment ④");
        }
        expectations.check(header, name);
        if (!Arrays.equals(header.contentHash(), contentHash)) {
            throw new SynxException(SynxError.MANIFEST, name, "content_hash " + SynxDigests.hex(header.contentHash())
                    + ", the release is " + SynxDigests.hex(contentHash));
        }
        final OverlaySegment segment = new OverlaySegment(number, path, mapping, null, mapping.byteSize());
        long offset = SynxFormat.HEADER_BYTES;
        while (offset + RECORD_HEADER_BYTES <= mapping.byteSize()) {
            final long length = Integer.toUnsignedLong(mapping.get(INT, offset + 16));
            if (length < SynxFormat.SEGMENT_TABLE_BYTES) break;
            final int chunkX = mapping.get(INT, offset);
            final int chunkZ = mapping.get(INT, offset + 4);
            final String fault = segment.fault(offset, length, chunkX, chunkZ);
            if (fault != null) {
                LOGGER.error("Overlay segment {} is cut at offset {}: {}; that record and the rest of the segment are dropped",
                        name, offset, fault);
                break;
            }
            segment.confirmed(CoordConversion.chunkIndex(chunkX, chunkZ), offset);
            sink.record(segment, offset, mapping.get(LONG, offset + 8), segment.present(offset));
            offset = align(offset + RECORD_HEADER_BYTES + length);
        }
        // Sealed: what counts is where its records end, not the length of the sparse file
        segment.position = offset;
        return segment;
    }

    /** What is wrong with the record at {@code offset}, null when it reads. */
    private @Nullable String fault(long offset, long length, int chunkX, int chunkZ) {
        if (offset + RECORD_HEADER_BYTES + length > mapping.byteSize()) {
            return "a record of " + length + " bytes runs past the end of the file";
        }
        final CRC32C crc = new CRC32C();
        crc.update(mapping.asSlice(offset, CRC_COVERED_HEADER_BYTES).asByteBuffer());
        crc.update(mapping.asSlice(offset + RECORD_HEADER_BYTES, length).asByteBuffer());
        final int expected = mapping.get(INT, offset + CRC_COVERED_HEADER_BYTES);
        if ((int) crc.getValue() != expected) {
            return "crc32c " + Integer.toHexString(expected) + ", the record hashes to " + Long.toHexString(crc.getValue());
        }
        if (SynxSlot.parse(chunkX, chunkZ, mapping.asSlice(offset + RECORD_HEADER_BYTES, length), false)
                instanceof SynxSlot.Failed failed) {
            return failed.fault() + " " + failed.detail();
        }
        return null;
    }

    private static long align(long offset) {
        return (offset + SynxFormat.RECORD_ALIGNMENT - 1) & -SynxFormat.RECORD_ALIGNMENT;
    }

    /**
     * Where the segment is written to: the end of its last record, 8 byte aligned, the header counted;
     * for the segment taking records the position the next one goes to ({@code overlay_bytes}, M3-S).
     */
    long written() {
        return position;
    }

    /** Whether a record of {@code length} bytes still fits behind the last one. */
    boolean fits(long length) {
        return position + RECORD_HEADER_BYTES + length <= mapping.byteSize();
    }

    /** Writes a record of {@code record} behind the last one, not forced, and returns its offset. */
    long append(OverlayTuning.Disk disk, int chunkX, int chunkZ, long generation, MemorySegment record) throws IOException {
        final int length = Math.toIntExact(record.byteSize());
        final ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(align(RECORD_HEADER_BYTES + length)))
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(chunkX).putInt(chunkZ).putLong(generation).putInt(length).putInt(0).put(record.asByteBuffer());
        final CRC32C crc = new CRC32C();
        crc.update(bytes.array(), 0, CRC_COVERED_HEADER_BYTES);
        crc.update(bytes.array(), RECORD_HEADER_BYTES, length);
        bytes.putInt(CRC_COVERED_HEADER_BYTES, (int) crc.getValue()).clear();
        return write(disk, bytes);
    }

    /** Writes the record at {@code offset} of {@code from} behind the last one as it is, record header included. */
    long copy(OverlayTuning.Disk disk, OverlaySegment from, long offset) throws IOException {
        final long length = RECORD_HEADER_BYTES + Integer.toUnsignedLong(from.mapping.get(INT, offset + 16));
        final ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(align(length)));
        bytes.put(from.mapping.asSlice(offset, length).asByteBuffer()).clear();
        return write(disk, bytes);
    }

    /** The length of the record body at {@code offset}. */
    long length(long offset) {
        return Integer.toUnsignedLong(mapping.get(INT, offset + 16));
    }

    private long write(OverlayTuning.Disk disk, ByteBuffer bytes) throws IOException {
        final long offset = position;
        disk.write(requireChannel(), bytes, offset);
        position = offset + bytes.capacity();
        return offset;
    }

    private FileChannel requireChannel() {
        final FileChannel open = channel;
        if (open == null) throw new IllegalStateException(path + " is sealed");
        return open;
    }

    void force(OverlayTuning.Disk disk) throws IOException {
        disk.force(requireChannel());
    }

    /** Counts the record at {@code offset} as confirmed. */
    void confirmed(long key, long offset) {
        if (records == keys.length) {
            keys = Arrays.copyOf(keys, records * 2);
            offsets = Arrays.copyOf(offsets, records * 2);
        }
        keys[records] = key;
        offsets[records] = offset;
        records++;
    }

    int records() {
        return records;
    }

    long key(int record) {
        return keys[record];
    }

    long offset(int record) {
        return offsets[record];
    }

    /** The record at {@code offset}, a view of the mapping; any thread. */
    SynxSlot.Present present(long offset) {
        final int chunkX = mapping.get(INT, offset);
        final int chunkZ = mapping.get(INT, offset + 4);
        final MemorySegment record = mapping.asSlice(offset + RECORD_HEADER_BYTES, length(offset));
        if (SynxSlot.parse(chunkX, chunkZ, record, false) instanceof SynxSlot.Present present) return present;
        throw new IllegalStateException(path + " has no overlay record at " + offset);
    }

    /** Takes no more records. */
    void seal() {
        final FileChannel open = channel;
        if (open == null) return;
        channel = null;
        try {
            open.close();
        } catch (IOException e) {
            LOGGER.warn("Overlay segment {} did not close: {}", path.getFileName(), e.toString());
        }
    }

    /** Unlinks the file; the mapping stays readable for whatever still holds a record of it. */
    void delete() throws IOException {
        seal();
        Files.deleteIfExists(path);
    }

    String name() {
        return path.getFileName().toString();
    }
}
