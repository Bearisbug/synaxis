package io.github.bearisbug.synaxis.synx;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** One chunk's record in a region file, an overlay segment or the pending table of the overlay store. */
public sealed interface SynxSlot {
    /**
     * Reads the segment length table of one record and slices its four segments out. Region files
     * and overlay segment files lay a record out the same way (API-M3-001, API-M3-007); the caller
     * has checked the record's range and {@code crc32c} where it reads it from a file; the pending table
     * parses an encoding it has just made.
     *
     * @param record     the record, from its segment length table to its last byte
     * @param hasColumns whether every record of the file carries segment ④
     * @return {@link Present}, or {@link Failed} with {@link SynxFault#MALFORMED} when the table
     *         disagrees with the record length or with the fixed segment sizes
     */
    static SynxSlot parse(int chunkX, int chunkZ, MemorySegment record, boolean hasColumns) {
        final ValueLayout.OfInt table = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        final long length = record.byteSize();
        final long packet = Integer.toUnsignedLong(record.get(table, 0));
        final long navigation = Integer.toUnsignedLong(record.get(table, 4));
        final long entities = Integer.toUnsignedLong(record.get(table, 8));
        final long columns = Integer.toUnsignedLong(record.get(table, 12));
        if (SynxFormat.SEGMENT_TABLE_BYTES + packet + navigation + entities + columns != length
                || navigation != SynxFormat.NAVIGATION_BYTES
                || columns != (hasColumns ? SynxFormat.COLUMN_BYTES : 0)) {
            return new Failed(SynxFault.MALFORMED, "segments " + packet + "/" + navigation + "/" + entities
                    + "/" + columns + " in a record of " + length);
        }
        long position = SynxFormat.SEGMENT_TABLE_BYTES;
        final MemorySegment packetSegment = record.asSlice(position, packet);
        position += packet;
        final MemorySegment navigationSegment = record.asSlice(position, navigation);
        position += navigation;
        final MemorySegment entitySegment = record.asSlice(position, entities);
        position += entities;
        return new Present(chunkX, chunkZ, packetSegment, navigationSegment, entitySegment,
                record.asSlice(position, columns));
    }

    /**
     * A record whose segment table checks, and, read from a file, its range and {@code crc32c}. The four
     * segments are views into its memory: a mapping, alive as long as its arena, or the heap array of a
     * pending encoding; {@link #packet()} is the framed segment ① a shard writes to the socket as it is.
     */
    record Present(int chunkX, int chunkZ, MemorySegment packet, MemorySegment navigation,
                   MemorySegment entities, MemorySegment columns) implements SynxSlot {
        /** Copies the record out of the mapping and decodes segment ③. */
        public SynxChunkRecord record() {
            final SynxEntities.Tables tables = SynxEntities.decode(entityTables());
            return new SynxChunkRecord(chunkX, chunkZ, packet.toArray(ValueLayout.JAVA_BYTE),
                    navigation.toArray(ValueLayout.JAVA_BYTE), tables.blockEntities(), tables.staticEntities(),
                    columns.toArray(ValueLayout.JAVA_BYTE));
        }

        /** Segment ③ uncompressed, empty when the chunk has neither table. */
        byte[] entityTables() {
            return entities.byteSize() == 0 ? new byte[0] : SynxEntities.inflate(entities.asByteBuffer());
        }
    }

    /** No record for this chunk: {@code offset = 0}, or no region file at all. */
    record Absent() implements SynxSlot {
    }

    /** A record that cannot be read; the rest of the file is unaffected. */
    record Failed(SynxFault fault, String detail) implements SynxSlot {
    }
}
