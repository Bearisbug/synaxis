package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.nbt.EndBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block entity table of segment ①, which a chunk loads without reading ({@code docs/DESIGN-M3.md}
 * {@code API-M3-001} 长度上限): the first read of it, a materialization or a read of the light, refuses
 * a type id the engine does not know as it refuses a length out of bounds, naming the file and the chunk.
 */
class SynxSegmentOneBlockEntityTest {

    private static final int UNKNOWN_TYPE = 9_999;
    private static final int CHUNK_X = 10, CHUNK_Z = 5;
    private static final int X = CHUNK_X * 16 + 1, Y = 100, Z = CHUNK_Z * 16 + 1;

    @Test
    void anUnknownBlockEntityTypeInSegmentOneRefusesTheChangeNamingTheChunk() {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            final SynxChunkRecord plain = SynxTestMap.get().record(CHUNK_X, CHUNK_Z);
            final SynxChunkRecord unknown = new SynxChunkRecord(CHUNK_X, CHUNK_Z, withUnknownBlockEntity(plain, env.process()),
                    plain.navigation(), plain.blockEntities(), plain.staticEntities(), plain.columns());
            final MemoryOverlay overlay = new MemoryOverlay();
            overlay.put(MemoryOverlay.hit(unknown, 0, 3, 256));
            final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, overlay);
            final Instance instance = SynxWorlds.instance(env.process(), loader);
            final SynxChunk chunk = (SynxChunk) instance.loadChunk(CHUNK_X, CHUNK_Z).join();
            assertEquals(SynxChunk.Mode.HANDLE, chunk.mode(), "the chunk loads without reading the table");
            final Block before = instance.getBlock(X, Y, Z);

            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> instance.setBlock(X, Y, Z, Block.GOLD_BLOCK));
            assertEquals("chunk 10,5: the overlay segment seg.3.synx, segment ①: unknown block entity type id: "
                    + UNKNOWN_TYPE, refused.getMessage());
            assertEquals(SynxChunk.Mode.HANDLE, chunk.mode(), "the change is refused, the chunk keeps its record");
            assertEquals(before, instance.getBlock(X, Y, Z));
            final IllegalArgumentException light = assertThrows(IllegalArgumentException.class, chunk::currentLight);
            assertEquals(refused.getMessage(), light.getMessage());
            assertEquals(0, loader.stats().failed(), "nothing is counted as failed: the chunk was read as a handle");
        } finally {
            env.close();
        }
    }

    /**
     * A block entity count over the 1,048,576 blocks of the tallest world is refused before anything is
     * allocated for it, as an unknown type is: 1,048,577 and {@link Integer#MAX_VALUE} with no entry after
     * them refuse the change and the light naming the chunk, without an {@link OutOfMemoryError}, and the
     * instance takes a change in the next chunk.
     */
    @Test
    void aBlockEntityCountOverTheCapRefusesTheChangeNamingTheChunk() {
        final int[] counts = {1_048_577, Integer.MAX_VALUE};
        final Env env = Env.create(UnaryOperator.identity());
        try {
            final MemoryOverlay overlay = new MemoryOverlay();
            for (int i = 0; i < counts.length; i++) {
                final int count = counts[i];
                final SynxChunkRecord plain = SynxTestMap.get().record(CHUNK_X + i, CHUNK_Z);
                final byte[] packet = withBlockEntityTable(plain, env.process(),
                        buffer -> buffer.write(NetworkBuffer.VAR_INT, count));
                overlay.put(MemoryOverlay.hit(new SynxChunkRecord(plain.chunkX(), plain.chunkZ(), packet,
                        plain.navigation(), plain.blockEntities(), plain.staticEntities(), plain.columns()), 0, 3 + i, 256));
            }
            final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, overlay);
            final Instance instance = SynxWorlds.instance(env.process(), loader);
            for (int i = 0; i < counts.length; i++) {
                final int x = X + i * 16;
                final SynxChunk chunk = (SynxChunk) instance.loadChunk(CHUNK_X + i, CHUNK_Z).join();
                assertEquals(SynxChunk.Mode.HANDLE, chunk.mode(), "the chunk loads without reading the table");
                final Block before = instance.getBlock(x, Y, Z);

                final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                        () -> instance.setBlock(x, Y, Z, Block.GOLD_BLOCK), "count " + counts[i]);
                assertTrue(refused.getMessage().startsWith("chunk " + (CHUNK_X + i) + "," + CHUNK_Z
                        + ": the overlay segment seg." + (3 + i) + ".synx, segment ①: Block entity count ("), refused.getMessage());
                assertEquals(SynxChunk.Mode.HANDLE, chunk.mode(), "the change is refused, the chunk keeps its record");
                assertEquals(before, instance.getBlock(x, Y, Z));
                final IllegalArgumentException light = assertThrows(IllegalArgumentException.class, chunk::currentLight);
                assertEquals(refused.getMessage(), light.getMessage());
            }
            final int next = X + counts.length * 16;
            instance.loadChunk(CHUNK_X + counts.length, CHUNK_Z).join();
            instance.setBlock(next, Y, Z, Block.GOLD_BLOCK);
            assertEquals(Block.GOLD_BLOCK, instance.getBlock(next, Y, Z));
            assertEquals(0, loader.stats().failed(), "nothing is counted as failed: the chunks were read as handles");
        } finally {
            env.close();
        }
    }

    /** Segment ① of {@code record} with a block entity table of one entry whose type id no registry has. */
    private static byte[] withUnknownBlockEntity(SynxChunkRecord record, Registries registries) {
        return withBlockEntityTable(record, registries, buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, 1);
            buffer.write(NetworkBuffer.BYTE, (byte) 0);
            buffer.write(NetworkBuffer.SHORT, (short) Y);
            buffer.write(NetworkBuffer.VAR_INT, UNKNOWN_TYPE);
            buffer.write(NetworkBuffer.NBT, EndBinaryTag.endBinaryTag());
        });
    }

    /**
     * Segment ① of {@code record}, uncompressed, with the block entity table {@code table} writes. The
     * engine's {@code ChunkData} writes a table only from block entities it holds, so the packet is written
     * field by field.
     */
    private static byte[] withBlockEntityTable(SynxChunkRecord record, Registries registries, Consumer<NetworkBuffer> table) {
        final ChunkDataPacket packet = SynxChunkPacket.decode(record.packet(), registries);
        final NetworkBuffer.Type<Map<Heightmap.Type, long[]>> heightmaps = Heightmap.Type.NETWORK_TYPE
                .mapValue(NetworkBuffer.LONG_ARRAY, Heightmap.Type.values().length);
        final byte[] body = NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, SynxFormat.CHUNK_DATA_PACKET_ID);
            buffer.write(NetworkBuffer.INT, packet.chunkX());
            buffer.write(NetworkBuffer.INT, packet.chunkZ());
            buffer.write(heightmaps, packet.chunkData().heightmaps());
            buffer.write(NetworkBuffer.BYTE_ARRAY, packet.chunkData().data());
            table.accept(buffer);
            buffer.write(LightData.NETWORK_TYPE, packet.lightData());
        }, registries);
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        SynxChunkPacket.writeVarInt(frame, SynxChunkPacket.varIntSize(0) + body.length);
        SynxChunkPacket.writeVarInt(frame, 0);
        frame.writeBytes(body);
        return frame.toByteArray();
    }
}
