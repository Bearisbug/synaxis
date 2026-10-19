package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import com.sun.management.ThreadMXBean;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10 (surface 2): the readers of a map release and of segment ③ against
 * malformed bytes. A broken index entry or segment table has to come back as {@link SynxSlot.Failed}, a
 * record the loader cannot place loads as failed, and every length is held to its cap before anything is
 * allocated for it (M3-S, {@code API-M3-001} 长度上限). The failed loads name the file they read, or miss.
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

    /**
     * What segment ③ states has to be there before anything is allocated for it. Adventure-nbt only
     * compares a stated length with its accounting cap, so each list of a nest passes on its own and
     * allocates what it states: 8 MB a level for 2,000,000 elements, about 4.29 GB for 512 levels in 2.6 KB.
     * A table count only has to fit the bytes left, an entry takes 8 bytes or more.
     */
    @Test
    public void segmentThreeThatStatesMoreThanItHoldsIsRefusedBeforeItIsAllocated() {
        final ByteArrayOutputStream nested = new ByteArrayOutputStream();
        nested.writeBytes(new byte[]{10, 0, 0, 9, 0, 1, 'a'});
        for (int level = 0; level < 8; level++) {
            nested.write(9);
            nested.writeBytes(ByteBuffer.allocate(4).putInt(2_000_000).array());
        }
        final byte[] lists = segmentThree(List.of(), List.of(nested.toByteArray()));
        final byte[] table = new byte[16 << 20];
        ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN).putInt(table.length - 4);
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (byte[] raw : new byte[][]{lists, table}) {
            final long before = threads.getCurrentThreadAllocatedBytes();
            assertThrows(IllegalArgumentException.class, () -> SynxEntities.decode(raw));
            final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            System.out.println("segment 3: " + raw.length + " B stating more than they hold allocated " + allocated
                    + " B before they were refused");
            assertTrue(allocated < 1L << 20, "allocated " + allocated);
        }
    }

    /**
     * Segment ③ content that would fail later, or not as malformed, is malformed like the rest
     * ({@code API-M3-001}): a byte array of length −1, a list of 1,000 end tags, and a handler that is
     * not a key, which the engine's dummy handler throws on when the chunk loads.
     */
    @Test
    public void malformedSegmentThreeContentIsRefusedAsAnIllegalArgument() {
        final byte[] negative = {10, 0, 0, 7, 0, 1, 'a', -1, -1, -1, -1, 0};
        final IllegalArgumentException array = assertThrows(IllegalArgumentException.class,
                () -> SynxEntities.decode(segmentThree(List.of(), List.of(negative))));
        assertTrue(array.getMessage().startsWith("unreadable NBT in segment ③: ")
                && array.getMessage().endsWith(" (static entity 1 of 1)"), array.getMessage());
        final byte[] ends = {10, 0, 0, 9, 0, 1, 'a', 0, 0, 0, 3, -24, 0};
        assertThrows(IllegalArgumentException.class, () -> SynxEntities.decode(segmentThree(List.of(), List.of(ends))));
        final IllegalArgumentException handler = assertThrows(IllegalArgumentException.class,
                () -> SynxEntities.decode(segmentThree(List.of("Bad Handler"), List.of())));
        assertEquals("handler is not a key: Bad Handler", handler.getMessage());
    }

    /**
     * The loader ({@code docs/DESIGN-M3.md} §11.1 {@code [*] → failed}): a block entity outside the sections
     * of the world, and a segment ① whose frame is fine but whose data is no zlib stream in a chunk with a
     * handler that ticks, which reads its block as the chunk loads. Both load as failed, the ERROR naming
     * the file the record came from.
     */
    @Test
    public void recordsTheLoaderCannotPlaceLoadAsFailed() {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            final SynxTestMap map = SynxTestMap.get();
            final SynxWorlds.Ticker ticker = new SynxWorlds.Ticker(SynxTestMap.TICKER_HANDLER);
            env.process().block().registerHandler(SynxTestMap.TICKER_HANDLER, () -> ticker);
            final SynxChunkRecord plain = map.record(10, 5);
            final SynxChunkRecord outside = new SynxChunkRecord(10, 5, plain.packet(), plain.navigation(),
                    List.of(new SynxBlockEntity(100, 0, "", CompoundBinaryTag.empty())), List.of(), plain.columns());
            final ByteArrayOutputStream noZlib = new ByteArrayOutputStream();
            final byte[] data = new byte[16];
            Arrays.fill(data, (byte) 1);
            SynxChunkPacket.writeVarInt(noZlib, SynxChunkPacket.varIntSize(100) + data.length);
            SynxChunkPacket.writeVarInt(noZlib, 100);
            noZlib.writeBytes(data);
            final SynxChunkRecord ticking = map.record(SynxTestMap.TICKER_CHUNK_X, SynxTestMap.TICKER_CHUNK_Z);
            final SynxChunkRecord undecodable = new SynxChunkRecord(ticking.chunkX(), ticking.chunkZ(), noZlib.toByteArray(),
                    ticking.navigation(), ticking.blockEntities(), ticking.staticEntities(), ticking.columns());
            final MemoryOverlay overlay = new MemoryOverlay();
            overlay.put(MemoryOverlay.hit(outside, 0, 3, 256));
            overlay.put(MemoryOverlay.hit(undecodable, 0, 4, 256));
            final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, overlay);
            final Instance instance = SynxWorlds.instance(env.process(), loader);
            try (LogCapture logs = LogCapture.of(SynxChunkLoader.class)) {
                assertEquals(SynxChunk.Mode.FAILED, ((SynxChunk) instance.loadChunk(10, 5).join()).mode());
                assertEquals(SynxChunk.Mode.FAILED,
                        ((SynxChunk) instance.loadChunk(ticking.chunkX(), ticking.chunkZ()).join()).mode());
                final List<String> errors = logs.messagesAt(Level.ERROR);
                assertEquals(2, errors.size(), errors.toString());
                assertTrue(errors.get(0).endsWith(" it loads as read only air: the overlay segment seg.3.synx, segment ③: "
                        + "a block entity in section 100, the world is sections 0 to 15"), errors.get(0));
                assertTrue(errors.get(1).endsWith(" it loads as read only air: the overlay segment seg.4.synx, segment ①: "
                        + "not a zlib stream"), errors.get(1));
            }
            assertEquals(2, loader.stats().failed());
        } finally {
            env.close();
        }
    }

    /** A chunk of the region whose region file the release does not have loads as failed, the ERROR naming the file. */
    @Test
    public void aChunkOfTheRegionWithoutItsRegionFileNamesTheFile() {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            final SynxChunkLoader loader = SynxWorlds.loader(SynxTestMap.get().buildDir,
                    new SynxManifest.Bounds(0, 0, 31, 40), SynxWorlds.WRITABLE, new MemoryOverlay());
            final Instance instance = SynxWorlds.instance(env.process(), loader);
            try (LogCapture logs = LogCapture.of(SynxChunkLoader.class)) {
                assertEquals(SynxChunk.Mode.FAILED, ((SynxChunk) instance.loadChunk(5, 35).join()).mode());
                final List<String> errors = logs.messagesAt(Level.ERROR);
                assertEquals(1, errors.size(), errors.toString());
                assertTrue(errors.getFirst().endsWith(
                        " it loads as read only air: the region file r.0.1.synx is missing"), errors.getFirst());
            }
            assertEquals(1, loader.stats().failed());
        } finally {
            env.close();
        }
    }

    /**
     * An instance of another world than the release's is a mistake of the caller, not a record that does not
     * read: the load throws, and no chunk is logged or counted as failed.
     */
    @Test
    public void anInstanceOfAnotherWorldThrowsWithoutAFailedChunk() {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay());
            final Instance overworld = env.process().instance().createInstanceContainer(loader);
            try (LogCapture logs = LogCapture.of(SynxChunkLoader.class)) {
                final CompletionException thrown = assertThrows(CompletionException.class,
                        () -> overworld.loadChunk(10, 5).join());
                final IllegalStateException world = assertInstanceOf(IllegalStateException.class, thrown.getCause());
                assertTrue(world.getMessage().startsWith("an instance of y -64 height 384, the release "),
                        world.getMessage());
                assertEquals(List.of(), logs.messagesAt(Level.ERROR));
            }
            assertEquals(0, loader.stats().failed());
        } finally {
            env.close();
        }
    }

    /**
     * Segment ③ uncompressed: a block entity at index 0 of section 0 with an empty compound for each of
     * {@code handlers}, then the static entities {@code nbt}, each the bytes of one NBT.
     */
    private static byte[] segmentThree(List<String> handlers, List<byte[]> nbt) {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final byte[] empty = {10, 0, 0, 0};
        raw.writeBytes(littleEndian(handlers.size()));
        for (String handler : handlers) {
            final byte[] name = handler.getBytes(StandardCharsets.UTF_8);
            raw.writeBytes(new byte[]{0, 0, 0, (byte) name.length});
            raw.writeBytes(name);
            raw.writeBytes(littleEndian(empty.length));
            raw.writeBytes(empty);
        }
        raw.writeBytes(littleEndian(nbt.size()));
        for (byte[] entity : nbt) {
            raw.writeBytes(littleEndian(entity.length));
            raw.writeBytes(entity);
        }
        return raw.toByteArray();
    }

    private static byte[] littleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
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
