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
     * segment files write a record this way. A record a shard would not read is not written (M3-S,
     * {@code API-M3-001} 长度上限): a segment ① frame longer than {@link SynxChunkPacket#MAX_FRAME_BYTES}, a
     * segment ③ over 16 MiB or one that does not read back.
     *
     * @throws IllegalArgumentException for such a record, naming the chunk
     */
    public byte[] encode(SynxSettings settings) {
        return encode(entities(), settings.compressionLevel());
    }

    /**
     * Segment ③ uncompressed, held to its cap and read back once.
     *
     * @throws IllegalArgumentException if it is over 16 MiB or does not read back, naming the chunk
     */
    byte[] entities() {
        try {
            return SynxEntities.encode(blockEntities, staticEntities);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("chunk " + chunkX + "," + chunkZ + ": " + e.getMessage(), e);
        }
    }

    /** {@link #encode(SynxSettings)} with segment ③ encoded already, uncompressed. */
    byte[] encode(byte[] entities, int compressionLevel) {
        // A frame length over the cap takes a 4 byte VarInt: 3 bytes of VarInt and the cap are the longest frame
        if (packet.length > SynxChunkPacket.MAX_FRAME_BYTES + 3) {
            throw new IllegalArgumentException("chunk " + chunkX + "," + chunkZ + ": segment ① is " + packet.length
                    + " bytes, a frame length over " + SynxChunkPacket.MAX_FRAME_BYTES);
        }
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
