package io.github.bearisbug.synaxis.network.packet.server.play.data;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockEntityType;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.instance.block.BlockUtils;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE_ARRAY;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG_ARRAY;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.NBT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.SHORT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.SORTED_NBT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ChunkData(Map<Heightmap.Type, long[]> heightmaps, byte[] data,
                        Map<Integer, Block> blockEntities) {
    /** The most bytes of section data a 26.2 client reads. */
    public static final int MAX_DATA_BYTES = 2_097_152;
    // Map files are read through NETWORK_TYPE too: at most one block entity for every block of the tallest world
    private static final int MAX_BLOCK_ENTITIES = LightData.MAX_SECTIONS * 4096;
    // The fewest bytes a block entity takes: xz, y, a one byte type id and an empty tag
    private static final int MIN_BLOCK_ENTITY_BYTES = 5;

    /**
     * Both maps are copied into a fixed iteration order (heightmap type ordinal, block index), which
     * is the order they are encoded in, and the NBT of each block entity is written with its keys
     * sorted: the same chunk state always yields the same packet bytes. {@code Map.copyOf} and
     * {@code Collectors.toUnmodifiableMap} do not qualify, their iteration order changes from one
     * JVM to the next.
     */
    public ChunkData {
        final Map<Heightmap.Type, long[]> sortedHeightmaps = new EnumMap<>(Heightmap.Type.class);
        sortedHeightmaps.putAll(heightmaps); // the long[] values are kept as given, not copied
        heightmaps = Collections.unmodifiableMap(sortedHeightmaps);
        data = data.clone();
        final Map<Integer, Block> sortedBlockEntities = new TreeMap<>();
        blockEntities.forEach((index, block) -> {
            if (block.blockEntityType() != null) sortedBlockEntities.put(index, block);
        });
        blockEntities = Collections.unmodifiableMap(sortedBlockEntities);
    }

    public static final NetworkBuffer.Type<ChunkData> NETWORK_TYPE = new NetworkBuffer.Type<>() {
        private static final NetworkBuffer.Type<Map<Heightmap.Type, long[]>> HEIGHTMAPS = Heightmap.Type.NETWORK_TYPE
                .mapValue(LONG_ARRAY, Heightmap.Type.values().length);
        private static final NetworkBuffer.Type<byte[]> DATA = NetworkBuffer.ByteArray(MAX_DATA_BYTES);

        @Override
        public void write(NetworkBuffer buffer, ChunkData value) {
            // Heightmaps
            buffer.write(HEIGHTMAPS, value.heightmaps);
            // Data
            buffer.write(BYTE_ARRAY, value.data);
            // Block entities
            buffer.write(VAR_INT, value.blockEntities.size());
            for (var entry : value.blockEntities.entrySet()) {
                final int index = entry.getKey();
                final Block block = entry.getValue();

                final Point point = CoordConversion.chunkBlockIndexGetGlobal(index, 0, 0);
                buffer.write(BYTE, (byte) (((point.blockX() & 15) << 4) | (point.blockZ() & 15))); // xz
                buffer.write(SHORT, (short) point.blockY()); // y

                buffer.write(BlockEntityType.NETWORK_TYPE, block.blockEntityType());
                final CompoundBinaryTag nbt = BlockUtils.extractClientNbt(block);
                assert nbt != null;
                buffer.write(SORTED_NBT, nbt); // block nbt
            }
        }

        @Override
        public ChunkData read(NetworkBuffer buffer) {
            return new ChunkData(buffer.read(HEIGHTMAPS), buffer.read(DATA), readBlockEntities(buffer));
        }
    };

    private static Map<Integer, Block> readBlockEntities(NetworkBuffer reader) {
        final int size = reader.read(VAR_INT);
        Check.argCondition(size < 0 || size > MAX_BLOCK_ENTITIES,
                "Block entity count ({0}) is out of the allowed range (0..{1})", size, MAX_BLOCK_ENTITIES);
        // Sized by the entries the bytes left can hold, not by the count they declare
        final Map<Integer, Block> blockEntities = HashMap.newHashMap(
                (int) Math.min(size, reader.readableBytes() / MIN_BLOCK_ENTITY_BYTES));
        for (int i = 0; i < size; i++) {
            final byte xz = reader.read(BYTE);
            final short y = reader.read(SHORT);
            final BlockEntityType blockEntity = reader.read(BlockEntityType.NETWORK_TYPE);
            // Vanilla sends a TAG_END when the block entity has no client-side NBT.
            final BinaryTag nbt = reader.read(NBT);
            final Block block = Block.fromKey(blockEntity.key());
            if (block == null) continue;
            final int index = CoordConversion.chunkBlockIndex(xz >> 4, y, xz & 15);
            blockEntities.put(index, nbt instanceof CompoundBinaryTag compound ? block.withNbt(compound) : block);
        }
        return blockEntities;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ChunkData(
                Map<Heightmap.Type, long[]> heightmaps1, byte[] data1, Map<Integer, Block> entities
        ))) return false;
        return Arrays.equals(data(), data1) && blockEntities().equals(entities) && heightmaps().equals(heightmaps1);
    }

    @Override
    public int hashCode() {
        int result = heightmaps().hashCode();
        result = 31 * result + Arrays.hashCode(data());
        result = 31 * result + blockEntities().hashCode();
        return result;
    }

    public record Section(short blockCount, short liquidCount, Palette blockStates, Palette biomes) {
        public static NetworkBuffer.Type<Section> networkType(int biomeCount) {
            return NetworkBufferTemplate.template(
                    SHORT, Section::blockCount,
                    SHORT, Section::liquidCount,
                    Palette.BLOCK_SERIALIZER, Section::blockStates,
                    Palette.biomeSerializer(biomeCount), Section::biomes,
                    Section::new
            );
        }
    }
}
