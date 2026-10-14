package io.github.bearisbug.synaxis.synx;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * A memory mapped, read only {@code .synx} region file, API-M3-001.
 * <p>
 * The mapping belongs to the arena the caller passes in: the shard maps its region files into
 * {@code Arena.ofShared()} once, in {@link SynxBuildVerifier#open}, and keeps them for the life of
 * the process (API-M3-002); the baker's verify maps them into an arena it closes when done. Every
 * {@link SynxSlot.Present} is a view into the mapping and is only valid while that arena is open.
 * Overlay segment files are not region files: {@link #of} refuses them. The mapping is lazy;
 * opening reads the header and nothing else.
 */
public final class SynxRegion {
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final String name;
    private final MemorySegment file;
    private final SynxHeader header;

    private SynxRegion(String name, MemorySegment file, SynxHeader header) {
        this.name = name;
        this.file = file;
        this.header = header;
    }

    /**
     * Maps a region file and checks what decides whether it can be read at all.
     *
     * @throws SynxException {@link SynxError#TRUNCATED} if the file ends inside the header or the index,
     *                       {@link SynxError#MAGIC} for an unknown magic, format version or flag, or an overlay segment file
     */
    public static SynxRegion open(Path path, Arena arena) throws IOException, SynxException {
        return of(path.getFileName().toString(), map(path, arena));
    }

    /** Maps the whole file into {@code arena}, read only; nothing of it is read yet. */
    static MemorySegment map(Path path, Arena arena) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
    }

    /**
     * A region file already mapped, {@link #open} once the mapping is there.
     *
     * @param name the file name, for the messages
     */
    static SynxRegion of(String name, MemorySegment file) throws SynxException {
        final SynxHeader header = SynxHeader.decode(file, name);
        if ((header.flags() & SynxFormat.FLAG_OVERLAY) != 0) {
            throw new SynxException(SynxError.MAGIC, name, "an overlay segment file, not a region file");
        }
        if (file.byteSize() < SynxFormat.RECORDS_START) {
            throw new SynxException(SynxError.TRUNCATED, name, file.byteSize() + " bytes, header and index are "
                    + SynxFormat.RECORDS_START);
        }
        return new SynxRegion(name, file, header);
    }

    public SynxHeader header() {
        return header;
    }

    /** The length of the mapping, which is the length of the file when it was mapped. */
    long byteSize() {
        return file.byteSize();
    }

    /** The header against the running process, see {@link SynxExpectations#check}. */
    public void checkAgainst(SynxExpectations expectations) throws SynxException {
        expectations.check(header, name);
    }

    /** SHA-256 of every byte after the index against {@code payload_sha256}; reads the whole file. */
    public void verifyPayload() throws SynxException {
        final MessageDigest digest = SynxDigests.sha256();
        digest.update(file.asSlice(SynxFormat.RECORDS_START).asByteBuffer());
        final byte[] actual = digest.digest();
        if (!Arrays.equals(actual, header.payloadSha256())) {
            throw new SynxException(SynxError.PAYLOAD_HASH, name, "payload_sha256 " + SynxDigests.hex(header.payloadSha256())
                    + ", the bytes hash to " + SynxDigests.hex(actual));
        }
    }

    /**
     * The index entry of a chunk of this region, checked against the file.
     *
     * @throws IllegalArgumentException if the chunk is in another region
     */
    public SynxSlot slot(int chunkX, int chunkZ) {
        if (SynxFormat.region(chunkX) != header.regionX() || SynxFormat.region(chunkZ) != header.regionZ()) {
            throw new IllegalArgumentException("chunk " + chunkX + "," + chunkZ + " is not in " + name);
        }
        final long entry = SynxFormat.HEADER_BYTES + (long) SynxFormat.index(chunkX, chunkZ) * SynxFormat.INDEX_ENTRY_BYTES;
        final long offset = file.get(LONG, entry);
        if (offset == 0) return new SynxSlot.Absent();
        final long length = Integer.toUnsignedLong(file.get(INT, entry + 8));
        final int crc = file.get(INT, entry + 12);
        if (offset < SynxFormat.RECORDS_START || offset % SynxFormat.RECORD_ALIGNMENT != 0
                || length < SynxFormat.SEGMENT_TABLE_BYTES || offset > file.byteSize() - length) {
            return new SynxSlot.Failed(SynxFault.OFFSET_OUT_OF_RANGE, "offset " + Long.toUnsignedString(offset)
                    + " length " + length + " in a file of " + file.byteSize() + " bytes; a record starts at "
                    + SynxFormat.RECORDS_START + " or later on the " + SynxFormat.RECORD_ALIGNMENT
                    + " byte grid, is at least " + SynxFormat.SEGMENT_TABLE_BYTES + " bytes and ends inside the file");
        }
        final MemorySegment record = file.asSlice(offset, length);
        final CRC32C actual = new CRC32C();
        actual.update(record.asByteBuffer());
        if ((int) actual.getValue() != crc) {
            return new SynxSlot.Failed(SynxFault.CRC_MISMATCH, "crc32c " + Integer.toHexString(crc)
                    + ", the record hashes to " + Long.toHexString(actual.getValue()));
        }
        return SynxSlot.parse(chunkX, chunkZ, record, header.hasColumns());
    }

    /** Index entries with a record, whether or not the record reads. */
    public int chunkCount() {
        int count = 0;
        for (int i = 0; i < SynxFormat.INDEX_ENTRIES; i++) {
            if (file.get(LONG, SynxFormat.HEADER_BYTES + (long) i * SynxFormat.INDEX_ENTRY_BYTES) != 0) count++;
        }
        return count;
    }

    /**
     * Recomputes {@code region_content_hash} from the records, the way the writer computed it; the
     * baker's {@code verify} compares it with the header.
     *
     * @throws IllegalStateException if a record fails to read
     */
    public byte[] computeRegionContentHash() {
        final MessageDigest content = SynxDigests.sha256();
        for (int i = 0; i < SynxFormat.INDEX_ENTRIES; i++) {
            final int chunkX = (header.regionX() << SynxFormat.REGION_SHIFT) + (i & (SynxFormat.CHUNKS_PER_SIDE - 1));
            final int chunkZ = (header.regionZ() << SynxFormat.REGION_SHIFT) + (i >> SynxFormat.REGION_SHIFT);
            switch (slot(chunkX, chunkZ)) {
                case SynxSlot.Absent _ -> {
                }
                case SynxSlot.Failed failed -> throw new IllegalStateException(name + " chunk " + chunkX + ","
                        + chunkZ + ": " + failed.fault() + " " + failed.detail());
                case SynxSlot.Present present -> {
                    content.update(SynxChunkPacket.body(present.packet()));
                    content.update(present.navigation().asByteBuffer());
                    content.update(present.entityTables());
                    content.update(present.columns().asByteBuffer());
                }
            }
        }
        return content.digest();
    }
}
