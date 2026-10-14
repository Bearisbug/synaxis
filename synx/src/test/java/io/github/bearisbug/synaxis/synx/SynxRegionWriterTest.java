package io.github.bearisbug.synaxis.synx;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout rules of API-M3-001 that the vectors do not spell out on their own. */
public class SynxRegionWriterTest {

    private static final byte[] NO_HASH = new byte[32];

    @Test
    public void emptyRegionIsHeaderAndIndexOnly(@TempDir Path dir) throws IOException {
        final SynxRegionWriter.Encoded empty = new SynxRegionWriter(3, -4, SynxVectors.settings()).encode(dir.resolve("empty.part"));
        assertEquals(0, empty.chunkCount());
        assertEquals(SynxFormat.RECORDS_START, empty.fileSize());
        assertEquals(SynxFormat.RECORDS_START, SynxVectors.bytes(empty, NO_HASH, dir).length);
        assertArrayEquals(SynxDigests.sha256(new byte[0]), empty.payloadSha256());
    }

    @Test
    public void recordsAreAlignedAndCarryTheirCrc(@TempDir Path dir) throws IOException {
        final byte[] file = SynxVectors.vector("two-by-two-lit").bytes(SynxVectors.settings(), NO_HASH, dir);
        final ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        long previousEnd = SynxFormat.RECORDS_START;
        for (int index : new int[]{30, 31, 62, 63}) {
            final int entry = SynxFormat.HEADER_BYTES + index * SynxFormat.INDEX_ENTRY_BYTES;
            final long offset = buffer.getLong(entry);
            final int length = buffer.getInt(entry + 8);
            assertEquals(0, offset % SynxFormat.RECORD_ALIGNMENT, "record " + index + " is 8 byte aligned");
            assertEquals((previousEnd + 7) & ~7L, offset, "records follow each other in index order");
            final CRC32C crc = new CRC32C();
            crc.update(file, (int) offset, length);
            assertEquals((int) crc.getValue(), buffer.getInt(entry + 12), "crc32c of record " + index);
            int segments = 0;
            for (int s = 0; s < 4; s++) segments += buffer.getInt((int) offset + 4 * s);
            assertEquals(length, SynxFormat.SEGMENT_TABLE_BYTES + segments);
            assertEquals(SynxFormat.NAVIGATION_BYTES, buffer.getInt((int) offset + 4));
            previousEnd = offset + length;
        }
        assertEquals(previousEnd, file.length, "the file ends with the last record");
    }

    @Test
    public void chunkWithoutBlockOrStaticEntitiesHasAnEmptySegmentThree(@TempDir Path dir) throws Exception {
        final byte[] file = SynxVectors.vector("one-chunk-flat").bytes(SynxVectors.settings(), NO_HASH, dir);
        final ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        final long offset = buffer.getLong(SynxFormat.HEADER_BYTES + SynxFormat.index(5, 7) * SynxFormat.INDEX_ENTRY_BYTES);
        assertEquals(0, buffer.getInt((int) offset + 8));
        assertEquals(SynxFormat.COLUMN_BYTES, buffer.getInt((int) offset + 12));
    }

    @Test
    public void writtenFileReadsBack(@TempDir Path dir) throws Exception {
        final SynxSettings settings = SynxVectors.settings();
        final SynxRegionWriter writer = new SynxRegionWriter(0, -1, settings);
        final SynxChunkRecord record = SynxVectors.chunk(3, -27, true, true).toRecord(settings);
        writer.add(record);
        final Path file = dir.resolve(SynxFormat.regionFileName(0, -1));
        final Path staging = dir.resolve("r.0.-1.synx.part");
        final SynxRegionWriter.Encoded encoded = writer.encode(staging);
        encoded.write(file, NO_HASH);
        assertFalse(Files.exists(staging), "the staging file becomes the region file");
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(file, arena);
            region.verifyPayload();
            final SynxChunkRecord read = assertInstanceOf(SynxSlot.Present.class, region.slot(3, -27)).record();
            assertEquals(record.staticEntities(), read.staticEntities());
            assertEquals(3, read.blockEntities().size());
            assertEquals("synaxis:loot_chest", read.blockEntities().get(1).handler());
        }
        assertEquals("r.0.-1.synx", file.getFileName().toString());
        assertEquals(Files.size(file), encoded.fileSize());
    }

    @Test
    public void finishedRegionNeverReplacesAnExistingFile(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve(SynxFormat.regionFileName(0, 0));
        Files.writeString(file, "released");
        final SynxRegionWriter.Encoded encoded = SynxVectors.vector("one-chunk-flat").encode(SynxVectors.settings(), dir);
        assertThrows(FileAlreadyExistsException.class, () -> encoded.write(file, NO_HASH));
        assertEquals("released", Files.readString(file));
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(file), left.toList(), "the staging file is removed when the region cannot be finished");
        }
    }

    @Test
    public void failedEncodeLeavesNoStagingFile(@TempDir Path dir) {
        final SynxChunkRecord flat = SynxVectors.chunk(5, 7, false, false).toRecord(SynxVectors.settings());
        final SynxRegionWriter writer = new SynxRegionWriter(0, 0, SynxVectors.settings());
        writer.add(new SynxChunkRecord(5, 7, new byte[]{3, 1, 2}, flat.navigation(), List.of(), List.of(), flat.columns()));
        final Path staging = dir.resolve("r.0.0.synx.part");
        assertThrows(IllegalArgumentException.class, () -> writer.encode(staging), "segment ① is not a frame");
        assertFalse(Files.exists(staging));
    }

    @Test
    public void settingsCompareByContent() {
        final SynxSettings settings = SynxVectors.settings();
        assertEquals(settings, SynxVectors.settings());
        assertEquals(settings.hashCode(), SynxVectors.settings().hashCode());
        assertNotEquals(settings, SynxVectors.settings(9));
    }

    @Test
    public void readerFailsARecordWhoseSegmentTableDisagrees(@TempDir Path dir) throws Exception {
        final byte[] file = Files.readAllBytes(SynxVectors.vector("one-chunk-flat").file());
        final ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        final int entry = SynxFormat.HEADER_BYTES + SynxFormat.index(5, 7) * SynxFormat.INDEX_ENTRY_BYTES;
        final int offset = (int) buffer.getLong(entry);
        final int length = buffer.getInt(entry + 8);
        // Segment ② one byte shorter and segment ① one byte longer: the total still adds up, the
        // crc32c is made to match, only the fixed size of segment ② gives it away
        buffer.putInt(offset, buffer.getInt(offset) + 1);
        buffer.putInt(offset + 4, SynxFormat.NAVIGATION_BYTES - 1);
        final CRC32C crc = new CRC32C();
        crc.update(file, offset, length);
        buffer.putInt(entry + 12, (int) crc.getValue());
        final Path malformed = dir.resolve("malformed.synx");
        Files.write(malformed, file);
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(malformed, arena);
            assertEquals(SynxFault.MALFORMED, assertInstanceOf(SynxSlot.Failed.class, region.slot(5, 7)).fault());
            assertThrows(IllegalStateException.class, region::computeRegionContentHash);
            assertThrows(IllegalArgumentException.class, () -> region.slot(32, 7), "a chunk of another region");
        }

        // An overlay segment file (API-M3-007) is not a region file
        file[6] |= SynxFormat.FLAG_OVERLAY;
        final Path overlay = dir.resolve("seg.0.synx");
        Files.write(overlay, file);
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(SynxError.MAGIC, assertThrows(SynxException.class, () -> SynxRegion.open(overlay, arena)).error());
        }

        // Another magic, a newer format_version, an unknown flag and a file shorter than the header
        file[6] &= (byte) ~SynxFormat.FLAG_OVERLAY;
        final byte[] newer = file.clone();
        newer[4] = 2;
        final byte[] magic = file.clone();
        magic[0] = 's';
        final byte[] flag = file.clone();
        flag[6] |= 4;
        final String[] details = {"format_version 2, this reader knows 1",
                "magic 73594e58, a .synx file starts with 53594e58 (SYNX)", ", this reader knows 11", ""};
        final byte[][] refused = {newer, magic, flag, Arrays.copyOf(file, 100)};
        for (int i = 0; i < refused.length; i++) {
            final Path path = dir.resolve("refused.synx");
            Files.write(path, refused[i]);
            try (Arena arena = Arena.ofConfined()) {
                final SynxException error = assertThrows(SynxException.class, () -> SynxRegion.open(path, arena));
                assertEquals(refused[i].length == 100 ? SynxError.TRUNCATED : SynxError.MAGIC, error.error());
                assertTrue(error.getMessage().contains(details[i]), error.getMessage());
            }
        }
    }

    @Test
    public void writerRejectsWhatTheFormatCannotHold() {
        final SynxSettings settings = SynxVectors.settings();
        final SynxChunkRecord inside = SynxVectors.chunk(5, 7, false, false).toRecord(settings);
        final SynxRegionWriter writer = new SynxRegionWriter(0, 0, settings);
        writer.add(inside);
        assertThrows(IllegalArgumentException.class, () -> writer.add(inside), "the same chunk twice");
        assertThrows(IllegalArgumentException.class,
                () -> new SynxRegionWriter(1, 0, settings).add(inside), "a chunk of another region");

        final SynxChunkRecord noColumns = new SynxChunkRecord(5, 7, inside.packet(), inside.navigation(),
                List.of(), List.of(), new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> new SynxRegionWriter(0, 0, settings).add(noColumns),
                "has_columns promises segment ④ in every record");
        final SynxSettings withoutColumns = new SynxSettings(776, 256, -1, 0, 256, settings.registryDigest(), 1, 1, false);
        assertThrows(IllegalArgumentException.class, () -> new SynxRegionWriter(0, 0, withoutColumns).add(inside),
                "segment ④ in a file without has_columns");

        assertThrows(IllegalArgumentException.class, () -> new SynxChunkRecord(0, 0, inside.packet(), new byte[543],
                List.of(), List.of(), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SynxBlockEntity(0, 0, "x".repeat(256), CompoundBinaryTag.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SynxBlockEntity(128, 0, "", CompoundBinaryTag.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SynxBlockEntity(0, 0, "Synaxis:Door", CompoundBinaryTag.empty()),
                "a handler that is not a key");
    }
}
