package io.github.bearisbug.synaxis.synx;

import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The four segments of one chunk (API-M3-001), decoded apart from segment ①, which stays the framed
 * packet the shard writes to the socket as it is.
 *
 * @param packet         segment ①: {@link SynxChunkPacket#encode} output
 * @param navigation     segment ②: {@link SynxFormat#NAVIGATION_BYTES} bytes
 * @param blockEntities  segment ③, first table: every block entity of the chunk
 * @param staticEntities segment ③, second table: the complete NBT of each static entity
 * @param columns        segment ④: {@link SynxFormat#COLUMN_BYTES} bytes, or empty when the file has no columns
 */
public record SynxChunkRecord(int chunkX, int chunkZ, byte[] packet, byte[] navigation,
                              List<SynxBlockEntity> blockEntities, List<CompoundBinaryTag> staticEntities,
                              byte[] columns) {
    public SynxChunkRecord {
        if (navigation.length != SynxFormat.NAVIGATION_BYTES) {
            throw new IllegalArgumentException("segment ② is " + SynxFormat.NAVIGATION_BYTES + " bytes: " + navigation.length);
        }
        if (columns.length != 0 && columns.length != SynxFormat.COLUMN_BYTES) {
            throw new IllegalArgumentException("segment ④ is empty or " + SynxFormat.COLUMN_BYTES + " bytes: " + columns.length);
        }
        packet = packet.clone();
        navigation = navigation.clone();
        columns = columns.clone();
        blockEntities = List.copyOf(blockEntities);
        staticEntities = List.copyOf(staticEntities);
    }

    /**
     * The record as a file stores it (API-M3-001): the segment length table, then the four
     * segments, segment ③ compressed at the level of {@code settings}. Region files and overlay
     * segment files write a record this way.
     */
    public byte[] encode(SynxSettings settings) {
        return encode(SynxEntities.encode(blockEntities, staticEntities), settings.compressionLevel());
    }

    /** {@link #encode(SynxSettings)} with segment ③ encoded already, uncompressed. */
    byte[] encode(byte[] entities, int compressionLevel) {
        final byte[] storedEntities = entities.length == 0 ? entities : SynxChunkPacket.deflate(entities, compressionLevel);
        final ByteBuffer bytes = ByteBuffer.allocate(SynxFormat.SEGMENT_TABLE_BYTES + packet.length
                + navigation.length + storedEntities.length + columns.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(packet.length).putInt(navigation.length).putInt(storedEntities.length).putInt(columns.length);
        bytes.put(packet).put(navigation).put(storedEntities).put(columns);
        return bytes.array();
    }

    @Override
    public byte[] packet() {
        return packet.clone();
    }

    @Override
    public byte[] navigation() {
        return navigation.clone();
    }

    @Override
    public byte[] columns() {
        return columns.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SynxChunkRecord other
                && chunkX == other.chunkX && chunkZ == other.chunkZ
                && Arrays.equals(packet, other.packet)
                && Arrays.equals(navigation, other.navigation)
                && blockEntities.equals(other.blockEntities)
                && staticEntities.equals(other.staticEntities)
                && Arrays.equals(columns, other.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkX, chunkZ, Arrays.hashCode(packet), Arrays.hashCode(navigation),
                blockEntities, staticEntities, Arrays.hashCode(columns));
    }

    @Override
    public String toString() {
        return "SynxChunkRecord[" + chunkX + ", " + chunkZ + ", packet " + packet.length + " B, "
                + blockEntities.size() + " block entities, " + staticEntities.size() + " static entities]";
    }
}
