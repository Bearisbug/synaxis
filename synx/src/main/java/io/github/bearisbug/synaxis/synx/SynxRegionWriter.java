package io.github.bearisbug.synaxis.synx;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
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
     * Lays out every added record in {@code staging} and computes both region hashes. The records
     * go to the file one at a time, so the region is never held in memory a second time; the
     * header and the index follow in {@link Encoded#write}, once the map wide
     * {@code content_hash} is known. {@code staging} is created or overwritten, and has to be on
     * the file system of the region file it becomes. It is deleted if encoding fails.
     *
     * @throws IllegalArgumentException if a segment ① is not a well formed frame
     */
    public Encoded encode(Path staging) throws IOException {
        final ByteBuffer index = ByteBuffer.allocate(SynxFormat.INDEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final MessageDigest content = SynxDigests.sha256();
        final MessageDigest payload = SynxDigests.sha256();
        long position = SynxFormat.RECORDS_START;
        int chunks = 0;
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(staging,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            output.write(new byte[SynxFormat.RECORDS_START]);
            for (int i = 0; i < SynxFormat.INDEX_ENTRIES; i++) {
                final SynxChunkRecord record = records[i];
                if (record == null) continue;
                final long aligned = (position + SynxFormat.RECORD_ALIGNMENT - 1) & -SynxFormat.RECORD_ALIGNMENT;
                final byte[] padding = new byte[(int) (aligned - position)];
                output.write(padding);
                payload.update(padding);
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
                output.write(bytes.array());
                payload.update(bytes.array());
                position += bytes.capacity();

                content.update(SynxChunkPacket.body(packet));
                content.update(navigation);
                content.update(entities);
                content.update(columns);
                chunks++;
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(staging);
            throw e;
        }
        return new Encoded(regionX, regionZ, settings, staging, index.array(), position, content.digest(),
                payload.digest(), chunks);
    }

    /**
     * A region in its staging file with everything but the map wide {@code content_hash}, which
     * needs every region first.
     */
    public static final class Encoded {
        private final int regionX;
        private final int regionZ;
        private final SynxSettings settings;
        private final Path staging;
        private final byte[] index;
        private final long fileSize;
        private final byte[] regionContentHash;
        private final byte[] payloadSha256;
        private final int chunkCount;

        private Encoded(int regionX, int regionZ, SynxSettings settings, Path staging, byte[] index, long fileSize,
                        byte[] regionContentHash, byte[] payloadSha256, int chunkCount) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.settings = settings;
            this.staging = staging;
            this.index = index;
            this.fileSize = fileSize;
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

        /** The size of the finished region file, header and index included. */
        public long fileSize() {
            return fileSize;
        }

        /**
         * Fills in the header and the index and turns the staging file into {@code file}. An
         * existing file is never replaced, released files are immutable: {@code file} appears
         * complete or not at all. The staging file is gone afterwards either way.
         *
         * @throws java.nio.file.FileAlreadyExistsException if {@code file} exists
         */
        public void write(Path file, byte[] contentHash) throws IOException {
            try {
                final byte[] header = SynxHeader.of(settings, regionX, regionZ, contentHash, regionContentHash,
                        payloadSha256).encode();
                try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                    final ByteBuffer start = ByteBuffer.allocate(SynxFormat.RECORDS_START).put(header).put(index).flip();
                    while (start.hasRemaining()) channel.write(start, start.position());
                    channel.force(true);
                }
                // A hard link fails on an existing name, where a rename would replace it
                Files.createLink(file, staging);
            } finally {
                Files.deleteIfExists(staging);
            }
        }
    }
}
