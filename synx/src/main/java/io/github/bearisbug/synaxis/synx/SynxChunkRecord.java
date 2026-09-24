package io.github.bearisbug.synaxis.synx;

import net.kyori.adventure.nbt.CompoundBinaryTag;

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
