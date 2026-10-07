package io.github.bearisbug.synaxis.synx;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10 (surface 2): the readers of a map release and of segment ③ against
 * malformed bytes. A broken index entry or segment table has to come back as {@link SynxSlot.Failed}, and
 * every length is held to its cap before anything is allocated for it (M3-S, {@code API-M3-001} 长度上限).
 */
public class SynxParserBoundaryTest {

    private static final byte[] NO_HASH = new byte[32];
    private static final int CHUNK_X = 5, CHUNK_Z = 7;

    private static int entry() {
        return SynxFormat.HEADER_BYTES + SynxFormat.index(CHUNK_X, CHUNK_Z) * SynxFormat.INDEX_ENTRY_BYTES;
    }

    private static SynxSlot slotOf(byte[] file, Path dir, String name) throws Exception {
        final Path path = dir.resolve(name);
        Files.write(path, file);
        try (Arena arena = Arena.ofConfined()) {
            return SynxRegion.open(path, arena).slot(CHUNK_X, CHUNK_Z);
        }
    }

    @Test
    public void brokenIndexEntriesReadAsFailed(@TempDir Path dir) throws Exception {
        final byte[] valid = SynxVectors.vector("one-chunk-flat").bytes(SynxVectors.settings(), NO_HASH, dir);
        assertInstanceOf(SynxSlot.Present.class, slotOf(valid, dir, "valid.synx"));
        final long[][] entries = {
                {-1L, 64},                                   // offset past any file, as u64
                {Long.MIN_VALUE, 64},
                {SynxFormat.RECORDS_START + 1L, 64},         // not 8 byte aligned
                {SynxFormat.RECORDS_START, 0xFFFF_FFFFL},    // length past the file
                {SynxFormat.RECORDS_START, 8},               // shorter than the segment table
                {8, 64},                                     // inside the header
        };
        final List<String> notFailed = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            final byte[] bytes = valid.clone();
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(entry(), entries[i][0])
                    .putInt(entry() + 8, (int) entries[i][1]);
            final SynxSlot slot = slotOf(bytes, dir, "entry-" + i + ".synx");
            if (!(slot instanceof SynxSlot.Failed)) notFailed.add(i + ": " + slot);
        }
        assertEquals(List.of(), notFailed);
    }

    @Test
    public void segmentTablesThatDisagreeWithTheRecordReadAsMalformed(@TempDir Path dir) throws Exception {
        final byte[] valid = SynxVectors.vector("one-chunk-flat").bytes(SynxVectors.settings(), NO_HASH, dir);
        final ByteBuffer index = ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN);
        final int offset = (int) index.getLong(entry());
        final int length = index.getInt(entry() + 8);
        final int[][] tables = {
                {-1, SynxFormat.NAVIGATION_BYTES, 0, SynxFormat.COLUMN_BYTES},
                {0, SynxFormat.NAVIGATION_BYTES, -1, -1},
                {-1, -1, -1, -1},
                {length, 0, 0, 0},
        };
        for (int i = 0; i < tables.length; i++) {
            final byte[] bytes = valid.clone();
            final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int s = 0; s < 4; s++) buffer.putInt(offset + 4 * s, tables[i][s]);
            final CRC32C crc = new CRC32C();
            crc.update(bytes, offset, length);
            buffer.putInt(entry() + 12, (int) crc.getValue());
            final SynxSlot slot = slotOf(bytes, dir, "table-" + i + ".synx");
            final SynxSlot.Failed failed = assertInstanceOf(SynxSlot.Failed.class, slot, "table " + i);
            assertEquals(SynxFault.MALFORMED, failed.fault(), "table " + i);
        }
    }

    /**
     * Segment ③ is one zlib stream whose length is stored nowhere: {@link SynxEntities#inflate} stops at
     * 16 MiB (M3-S, {@code API-M3-001} 长度上限). 16 MiB of zeros deflate to about 16 KiB and come back whole,
     * one byte more is refused.
     */
    @Test
    public void segmentThreeInflatesToSixteenMebibytesAtMost() {
        final byte[] cap = SynxChunkPacket.deflate(new byte[16 << 20], 9);
        assertEquals(16 << 20, SynxEntities.inflate(ByteBuffer.wrap(cap)).length);
        final byte[] over = SynxChunkPacket.deflate(new byte[(16 << 20) + 1], 9);
        System.out.println("segment 3: " + over.length + " B that inflate to 16777217 B");
        assertThrows(IllegalArgumentException.class, () -> SynxEntities.inflate(ByteBuffer.wrap(over)));
    }

    /** A frame of segment ①: frame length, the uncompressed length as given, then {@code data}. */
    private static byte[] frame(byte[] dataLength, byte[] data) {
        final int frameLength = dataLength.length + data.length;
        final ByteBuffer frame = ByteBuffer.allocate(1 + frameLength);
        frame.put((byte) frameLength).put(dataLength).put(data);
        return frame.array();
    }

    /** A negative uncompressed length (a five byte VarInt) is refused like every other bad frame. */
    @Test
    public void aNegativeUncompressedLengthIsRefused() {
        final byte[] minusOne = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F};
        final byte[] frame = frame(minusOne, SynxChunkPacket.deflate(new byte[1], 9));
        assertThrows(IllegalArgumentException.class, () -> SynxChunkPacket.body(frame));
    }

    /**
     * The uncompressed length is held to the 8 MiB the vanilla client takes before anything is allocated
     * for it: a frame of 14 bytes stating 64 MiB is refused having allocated next to nothing, and so is one
     * stating 8,388,609 bytes that inflates to them.
     */
    @Test
    public void anUncompressedLengthOverTheCapIsRefusedBeforeItIsAllocated() {
        final byte[] sixtyFourMebibytes = {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0x20};
        final byte[] frame = frame(sixtyFourMebibytes, SynxChunkPacket.deflate(new byte[1], 9));
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();
        assertThrows(IllegalArgumentException.class, () -> SynxChunkPacket.body(frame));
        final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        System.out.println("segment 1: a " + frame.length + " B frame allocated " + allocated + " B before it was refused");
        assertTrue(allocated < 1L << 20, "allocated " + allocated);

        final ByteArrayOutputStream overCap = new ByteArrayOutputStream();
        final byte[] data = SynxChunkPacket.deflate(new byte[8_388_609], 9);
        SynxChunkPacket.writeVarInt(overCap, SynxChunkPacket.varIntSize(8_388_609) + data.length);
        SynxChunkPacket.writeVarInt(overCap, 8_388_609);
        overCap.writeBytes(data);
        assertThrows(IllegalArgumentException.class, () -> SynxChunkPacket.body(overCap.toByteArray()));
    }

    /**
     * A frame longer than a 3 byte VarInt states, 2,097,152 bytes, is refused: the vanilla client drops
     * the connection on it. This one is uncompressed, every byte as stated.
     */
    @Test
    public void aFrameOverTwoMebibytesIsRefused() {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        SynxChunkPacket.writeVarInt(frame, 2_097_152);
        SynxChunkPacket.writeVarInt(frame, 0);
        frame.writeBytes(new byte[2_097_151]);
        assertThrows(IllegalArgumentException.class, () -> SynxChunkPacket.body(frame.toByteArray()));
    }

    /**
     * The manifest reader refuses a name given twice in one object, as the shard directory reader does,
     * and a number that does not fit.
     */
    @Test
    public void theManifestRefusesADuplicateNameAndAnOversizedNumber() throws Exception {
        final SynxManifest manifest = new SynxManifest("vectors", SynxManifest.buildId("vectors", NO_HASH, 776), NO_HASH,
                "26.2", 776, NO_HASH, 1, 1, new SynxManifest.World(0, 64), SynxVectors.bounds(), List.of(), List.of(),
                new SynxManifest.Source("generator", null, null),
                new SynxManifest.Bake("2026-10-02T00:00:00Z", 1, 1, 1, "25"), new SynxManifest.Verify(0, 0, null));
        final String json = manifest.toJson();
        final String duplicated = json.replaceFirst("\"map_id\": \"" + manifest.mapId() + "\"",
                "\"map_id\": \"first\", \"map_id\": \"" + manifest.mapId() + "\"");
        assertTrue(duplicated.contains("\"first\""), "the duplicate went in");
        final SynxException twice = assertThrows(SynxException.class, () -> SynxManifest.fromJson(duplicated));
        assertEquals(SynxError.MANIFEST, twice.error());
        assertTrue(twice.getMessage().contains("$.map_id is given twice"), twice.getMessage());

        final String oversized = json.replaceFirst("\"min_y\": -?[0-9]+", "\"min_y\": 1e400000");
        final SynxException refused = assertThrows(SynxException.class, () -> SynxManifest.fromJson(oversized));
        assertEquals(SynxError.MANIFEST, refused.error());
    }
}
