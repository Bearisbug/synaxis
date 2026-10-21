package io.github.bearisbug.synaxis.network.packet.server.play.data;

import com.sun.management.ThreadMXBean;
import net.kyori.adventure.nbt.EndBinaryTag;
import io.github.bearisbug.synaxis.instance.block.BlockEntityType;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG_ARRAY;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.NBT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.SHORT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkData} is also read from map files ({@code .synx} segment ①), so every count and length
 * in it is bounded on read, and refused with {@link IllegalArgumentException} or
 * {@link IndexOutOfBoundsException} before it sizes an allocation.
 */
public class ChunkDataReadLimitsTest {

    /** 256 sections of 4,096 blocks. */
    private static final int MAX_BLOCK_ENTITIES = 1_048_576;
    /** What a 26.2 client reads at most. */
    private static final int MAX_DATA_BYTES = 2_097_152;

    /** No heightmaps, {@code dataBytes} bytes of section data, then a block entity table. */
    private static NetworkBuffer chunkData(int dataBytes, int blockEntityCount, int entriesSent) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(VAR_INT, 0);
        buffer.write(VAR_INT, dataBytes);
        buffer.write(RAW_BYTES, new byte[dataBytes]);
        buffer.write(VAR_INT, blockEntityCount);
        for (int i = 0; i < entriesSent; i++) {
            // 5 bytes, the least an entry takes: xz, y, the type id and an empty tag
            buffer.write(BYTE, (byte) i);
            buffer.write(SHORT, (short) ((i >> 8) - 2048));
            buffer.write(BlockEntityType.NETWORK_TYPE, BlockEntityType.CHEST);
            buffer.write(NBT, EndBinaryTag.endBinaryTag());
        }
        return buffer;
    }

    /** The bytes the calling thread allocates while {@code action} runs. */
    private static long allocatedBy(Runnable action) {
        final var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();
        action.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    private static void warmUp() {
        assertEquals(1, chunkData(0, 1, 1).read(ChunkData.NETWORK_TYPE).blockEntities().size());
    }

    @Test
    void aBlockEntityCountOfIntegerMaxValueIsRefusedBeforeAnyTableIsMade() {
        warmUp();
        final NetworkBuffer buffer = chunkData(0, Integer.MAX_VALUE, 1);
        final long allocated = allocatedBy(() ->
                assertThrows(IllegalArgumentException.class, () -> buffer.read(ChunkData.NETWORK_TYPE)));
        assertTrue(allocated < 1 << 20, allocated + " bytes allocated");
    }

    @Test
    void aBlockEntityTableIsSizedByTheBytesLeftRatherThanByItsCount() {
        warmUp();
        final NetworkBuffer buffer = chunkData(0, MAX_BLOCK_ENTITIES, 1);
        final long allocated = allocatedBy(() ->
                assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(ChunkData.NETWORK_TYPE)));
        assertTrue(allocated < 1 << 20, allocated + " bytes allocated");
    }

    @Test
    void aBlockEntityTableReadsUpTo1048576Entries() {
        assertEquals(MAX_BLOCK_ENTITIES, chunkData(0, MAX_BLOCK_ENTITIES, MAX_BLOCK_ENTITIES)
                .read(ChunkData.NETWORK_TYPE).blockEntities().size());
        assertThrows(IllegalArgumentException.class,
                () -> chunkData(0, MAX_BLOCK_ENTITIES + 1, 0).read(ChunkData.NETWORK_TYPE));
    }

    @Test
    void sectionDataReadsUpTo2MiB() {
        assertEquals(MAX_DATA_BYTES, chunkData(MAX_DATA_BYTES, 0, 0).read(ChunkData.NETWORK_TYPE).data().length);
        assertThrows(IllegalArgumentException.class,
                () -> chunkData(MAX_DATA_BYTES + 1, 0, 0).read(ChunkData.NETWORK_TYPE));
    }

    @Test
    void heightmapsReadUpToOnePerType() {
        final int types = Heightmap.Type.values().length;
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(VAR_INT, types + 1);
        for (int i = 0; i <= types; i++) {
            buffer.write(Heightmap.Type.NETWORK_TYPE, Heightmap.Type.values()[i % types]);
            buffer.write(LONG_ARRAY, new long[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> buffer.read(ChunkData.NETWORK_TYPE));
    }
}
