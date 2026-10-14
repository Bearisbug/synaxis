package io.github.bearisbug.synaxis.synx;

/**
 * Layout constants of the {@code .synx} region file, {@code docs/DESIGN-M3.md} API-M3-001.
 * <p>
 * Every multi-byte integer of the container (header, offset index, segment length table, segments ②
 * and ④, the tables of segment ③) is little endian; segment ① is the protocol's own big endian and
 * VarInt encoding, and NBT is big endian. A change to a layout constant is a layout change: it bumps
 * {@link #FORMAT_VERSION} and refreezes the test vectors in the same change ({@code docs/DESIGN-M3.md}
 * §24). {@link #CHUNK_DATA_PACKET_ID} follows the protocol instead: it changes with
 * {@code protocol_version} and a rebake, the vectors refrozen, {@code format_version} as it is.
 */
public final class SynxFormat {
    /** {@code SYNX} read as a little endian {@code int}. */
    public static final int MAGIC = 'S' | 'Y' << 8 | 'N' << 16 | 'X' << 24;
    public static final int FORMAT_VERSION = 1;

    public static final int HEADER_BYTES = 256;
    public static final int REGION_SHIFT = 5;
    public static final int CHUNKS_PER_SIDE = 1 << REGION_SHIFT;
    public static final int INDEX_ENTRIES = CHUNKS_PER_SIDE * CHUNKS_PER_SIDE;
    /** {@code {offset u64, length u32, crc32c u32}}. */
    public static final int INDEX_ENTRY_BYTES = 16;
    public static final int INDEX_BYTES = INDEX_ENTRIES * INDEX_ENTRY_BYTES;
    /** Where the first record may start, and where {@code payload_sha256} starts hashing. */
    public static final int RECORDS_START = HEADER_BYTES + INDEX_BYTES;
    public static final int RECORD_ALIGNMENT = 8;
    /** The four {@code u32} segment lengths in front of every record. */
    public static final int SEGMENT_TABLE_BYTES = 16;

    /** Where segment ② keeps its 256 {@code i16} surface heights, after the 32 byte walkable bit set. */
    public static final int NAVIGATION_SURFACE_Y = 32;
    /** Segment ②: the walkable bit set (32 B) and 256 {@code i16} surface heights. */
    public static final int NAVIGATION_BYTES = NAVIGATION_SURFACE_Y + 256 * 2;
    /** Segment ④: 256 columns of surface height {@code u16}, surface state id {@code u16}, sky light {@code u8}. */
    public static final int COLUMN_BYTES = 256 * 5;

    /** Header flag bit0: an overlay segment file of API-M3-007, which has no offset index. */
    public static final int FLAG_OVERLAY = 1;
    /** Header flag bit1: segment ④ is present in every record. */
    public static final int FLAG_HAS_COLUMNS = 1 << 1;
    public static final int KNOWN_FLAGS = FLAG_OVERLAY | FLAG_HAS_COLUMNS;

    /**
     * The play state id of {@code ChunkDataPacket} in protocol 776. Segment ① is a framed packet, so
     * the id is part of the stored bytes; a test holds it equal to the engine's packet registry.
     */
    public static final int CHUNK_DATA_PACKET_ID = 0x2D;

    private SynxFormat() {
    }

    /** The index entry of a chunk, {@code (chunk_z & 31) × 32 + (chunk_x & 31)}. */
    public static int index(int chunkX, int chunkZ) {
        return (chunkZ & (CHUNKS_PER_SIDE - 1)) * CHUNKS_PER_SIDE + (chunkX & (CHUNKS_PER_SIDE - 1));
    }

    public static int region(int chunkCoordinate) {
        return chunkCoordinate >> REGION_SHIFT;
    }

    /** {@code r.<rx>.<rz>.synx}. */
    public static String regionFileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".synx";
    }

    /** {@code t.<tile_x>.<tile_z>.hfp}, a far view pack under {@code farview/} of the release directory, API-M3-014. */
    public static String farViewFileName(int tileX, int tileZ) {
        return "t." + tileX + "." + tileZ + ".hfp";
    }

    /** {@code <map_id>/farview/<sha256 hex>.hfp}, a far view object under the export directory and the url root. */
    public static String farViewObject(String mapId, byte[] sha256) {
        return mapId + "/farview/" + SynxDigests.hex(sha256) + ".hfp";
    }
}
