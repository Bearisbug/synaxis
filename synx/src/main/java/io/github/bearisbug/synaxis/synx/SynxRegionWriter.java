package io.github.bearisbug.synaxis.synx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.zip.CRC32C;

/**
 * Writes one {@code .synx} region file, API-M3-001: header, offset index, then the records in index
 * order, each starting on an 8 byte boundary. Not thread safe; the baker uses one writer per region.
 */
public final class SynxRegionWriter {
    private final int regionX;
    private final int regionZ;
    private final SynxSettings settings;
    private final SynxChunkRecord[] records = new SynxChunkRecord[SynxFormat.INDEX_ENTRIES];

    public SynxRegionWriter(int regionX, int regionZ, SynxSettings settings) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.settings = settings;
    }

    /**
     * Adds the record of one chunk of this region.
     *
     * @throws IllegalArgumentException if the chunk lies in another region, is already added, or
     *                                  its segment ④ disagrees with {@link SynxSettings#hasColumns()}
     */
    public void add(SynxChunkRecord record) {
        if (SynxFormat.region(record.chunkX()) != regionX || SynxFormat.region(record.chunkZ()) != regionZ) {
            throw new IllegalArgumentException("chunk " + record.chunkX() + "," + record.chunkZ()
                    + " is not in region " + regionX + "," + regionZ);
        }
        final int index = SynxFormat.index(record.chunkX(), record.chunkZ());
        if (records[index] != null) {
            throw new IllegalArgumentException("chunk " + record.chunkX() + "," + record.chunkZ() + " added twice");
        }
        final int columns = record.columns().length;
        if (settings.hasColumns() != (columns == SynxFormat.COLUMN_BYTES)) {
            throw new IllegalArgumentException("chunk " + record.chunkX() + "," + record.chunkZ() + " has " + columns
                    + " bytes of segment ④, has_columns is " + settings.hasColumns());
        }
        records[index] = record;
    }

    /**
     * Lays out every added record and computes both region hashes.
     *
     * @throws IllegalArgumentException if a segment ① is not a well formed frame
     */
    public Encoded encode() {
        final ByteBuffer index = ByteBuffer.allocate(SynxFormat.INDEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        final MessageDigest content = SynxDigests.sha256();
        long position = SynxFormat.RECORDS_START;
        int chunks = 0;
        for (int i = 0; i < SynxFormat.INDEX_ENTRIES; i++) {
            final SynxChunkRecord record = records[i];
            if (record == null) continue;
            final long aligned = (position + SynxFormat.RECORD_ALIGNMENT - 1) & -SynxFormat.RECORD_ALIGNMENT;
            payload.writeBytes(new byte[(int) (aligned - position)]);
            position = aligned;

            final byte[] packet = record.packet();
            final byte[] navigation = record.navigation();
            final byte[] entities = SynxEntities.encode(record.blockEntities(), record.staticEntities());
            final byte[] storedEntities = entities.length == 0 ? entities
                    : SynxChunkPacket.deflate(entities, settings.compressionLevel());
            final byte[] columns = record.columns();

            final ByteBuffer bytes = ByteBuffer.allocate(SynxFormat.SEGMENT_TABLE_BYTES + packet.length
                    + navigation.length + storedEntities.length + columns.length).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(packet.length).putInt(navigation.length).putInt(storedEntities.length).putInt(columns.length);
            bytes.put(packet).put(navigation).put(storedEntities).put(columns);
            final CRC32C crc = new CRC32C();
            crc.update(bytes.array());
            index.putLong(i * SynxFormat.INDEX_ENTRY_BYTES, position)
                    .putInt(i * SynxFormat.INDEX_ENTRY_BYTES + 8, bytes.capacity())
                    .putInt(i * SynxFormat.INDEX_ENTRY_BYTES + 12, (int) crc.getValue());
            payload.writeBytes(bytes.array());
            position += bytes.capacity();

            content.update(SynxChunkPacket.body(packet));
            content.update(navigation);
            content.update(entities);
            content.update(columns);
            chunks++;
        }
        final byte[] payloadBytes = payload.toByteArray();
        return new Encoded(regionX, regionZ, settings, index.array(), payloadBytes, content.digest(),
                SynxDigests.sha256(payloadBytes), chunks);
    }

    /** A region with everything but the map wide {@code content_hash}, which needs every region first. */
    public static final class Encoded {
        private final int regionX;
        private final int regionZ;
        private final SynxSettings settings;
        private final byte[] index;
        private final byte[] payload;
        private final byte[] regionContentHash;
        private final byte[] payloadSha256;
        private final int chunkCount;

        private Encoded(int regionX, int regionZ, SynxSettings settings, byte[] index, byte[] payload,
                        byte[] regionContentHash, byte[] payloadSha256, int chunkCount) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.settings = settings;
            this.index = index;
            this.payload = payload;
            this.regionContentHash = regionContentHash;
            this.payloadSha256 = payloadSha256;
            this.chunkCount = chunkCount;
        }

        /**
         * SHA-256 over the records in index order, each contributing the uncompressed segment ①
         * body, segment ②, the uncompressed segment ③ and segment ④.
         */
        public byte[] regionContentHash() {
            return regionContentHash.clone();
        }

        /** SHA-256 over every byte after the offset index. */
        public byte[] payloadSha256() {
            return payloadSha256.clone();
        }

        public int chunkCount() {
            return chunkCount;
        }

        public byte[] toBytes(byte[] contentHash) {
            final byte[] header = SynxHeader.of(settings, regionX, regionZ, contentHash, regionContentHash, payloadSha256)
                    .encode();
            final byte[] file = new byte[SynxFormat.RECORDS_START + payload.length];
            System.arraycopy(header, 0, file, 0, SynxFormat.HEADER_BYTES);
            System.arraycopy(index, 0, file, SynxFormat.HEADER_BYTES, SynxFormat.INDEX_BYTES);
            System.arraycopy(payload, 0, file, SynxFormat.RECORDS_START, payload.length);
            return file;
        }

        /** Writes a new file; an existing one is never overwritten, released files are immutable. */
        public void write(Path file, byte[] contentHash) throws IOException {
            Files.write(file, toBytes(contentHash), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }
}
