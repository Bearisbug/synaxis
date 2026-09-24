package io.github.bearisbug.synaxis.synx;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** What the offset index holds for one chunk of a region. */
public sealed interface SynxSlot {
    /**
     * A record that passed the range, {@code crc32c} and segment table checks. The four segments
     * are views into the mapping and live as long as its arena; {@link #packet()} is the framed
     * segment ① a shard writes to the socket as it is.
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

    /** {@code offset = 0}: the file has no record for this chunk. */
    record Absent() implements SynxSlot {
    }

    /** A record that cannot be read; the rest of the file is unaffected. */
    record Failed(SynxFault fault, String detail) implements SynxSlot {
    }
}
