package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Reads segment ① back field by field, in packet order, without building a {@code ChunkDataPacket}
 * ({@code docs/DESIGN-M3.md} API-M3-005 读方块 and 物化, §6): the handle chunk only needs the section
 * palettes, materialization the palettes and the light.
 */
final class SynxPacketDecoder {
    private static final NetworkBuffer.Type<Map<Heightmap.Type, long[]>> HEIGHTMAPS = Heightmap.Type.NETWORK_TYPE
            .mapValue(NetworkBuffer.LONG_ARRAY, Heightmap.Type.values().length);

    /**
     * The palettes of every section, bottom up. They are shared through the section cache: read them,
     * copy them, never write them.
     */
    record Sections(Palette[] blocks, Palette[] biomes) {
    }

    /**
     * The palettes and the light of every section; a null light array is a section the packet marks
     * empty.
     */
    record Full(Sections sections, byte[] @Nullable [] sky, byte[] @Nullable [] block) {
    }

    private SynxPacketDecoder() {
    }

    /**
     * The section palettes of a framed segment ①. Stops after the section data: heightmaps are
     * skipped, block entities and light are not read at all.
     *
     * @throws IllegalArgumentException if the bytes are not a chunk packet of {@code sectionCount} sections
     */
    static Sections sections(MemorySegment framed, Registries registries, int sectionCount) {
        final NetworkBuffer buffer = body(framed, registries);
        buffer.read(HEIGHTMAPS);
        return sections(buffer.read(NetworkBuffer.BYTE_ARRAY), registries, sectionCount);
    }

    /**
     * The section palettes and the light of a framed segment ①.
     *
     * @throws IllegalArgumentException if the bytes are not a chunk packet of {@code sectionCount} sections
     */
    static Full full(MemorySegment framed, Registries registries, int sectionCount) {
        final NetworkBuffer buffer = body(framed, registries);
        final ChunkData chunkData = buffer.read(ChunkData.NETWORK_TYPE);
        final LightData light = buffer.read(LightData.NETWORK_TYPE);
        if (buffer.readableBytes() != 0) throw new IllegalArgumentException(buffer.readableBytes() + " bytes after the packet");
        return new Full(sections(chunkData.data(), registries, sectionCount),
                light(light.skyMask(), light.skyLight(), sectionCount),
                light(light.blockMask(), light.blockLight(), sectionCount));
    }

    /** The packet id and chunk coordinates read, the rest of the body left. */
    private static NetworkBuffer body(MemorySegment framed, Registries registries) {
        final byte[] body = SynxChunkPacket.body(framed);
        final NetworkBuffer buffer = NetworkBuffer.wrap(body, 0, body.length, registries);
        final int id = buffer.read(NetworkBuffer.VAR_INT);
        if (id != SynxFormat.CHUNK_DATA_PACKET_ID) throw new IllegalArgumentException("packet id " + id + " is not ChunkDataPacket");
        buffer.read(NetworkBuffer.INT);
        buffer.read(NetworkBuffer.INT);
        return buffer;
    }

    private static Sections sections(byte[] data, Registries registries, int sectionCount) {
        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(registries.biome().size());
        final NetworkBuffer buffer = NetworkBuffer.wrap(data, 0, data.length, registries);
        final Palette[] blocks = new Palette[sectionCount];
        final Palette[] biomes = new Palette[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            if (buffer.readableBytes() == 0) throw new IllegalArgumentException(i + " sections, the world has " + sectionCount);
            final ChunkData.Section section = buffer.read(type);
            blocks[i] = section.blockStates();
            biomes[i] = section.biomes();
        }
        if (buffer.readableBytes() != 0) throw new IllegalArgumentException("more sections than the " + sectionCount + " of the world");
        return new Sections(blocks, biomes);
    }

    /** Mask bit {@code i + 1} is section {@code i}; bit 0 and the last bit are the sections below and above the world. */
    private static byte[] @Nullable [] light(BitSet mask, List<byte[]> arrays, int sectionCount) {
        final byte[][] result = new byte[sectionCount][];
        int next = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
            if (next >= arrays.size()) throw new IllegalArgumentException("light mask with more bits than arrays");
            final byte[] array = arrays.get(next++);
            if (bit >= 1 && bit <= sectionCount) result[bit - 1] = array;
        }
        return result;
    }
}
