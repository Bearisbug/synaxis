package io.github.bearisbug.synaxis.baker.anvil;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read-only view of one vanilla region file ({@code r.<x>.<z>.mca}): a 4 KiB table of chunk locations,
 * a 4 KiB table of timestamps, then each chunk as a 4 byte length, a compression byte and the
 * compressed NBT, starting on a 4 KiB sector boundary. Opened with {@link StandardOpenOption#READ}
 * only; positional reads, so one instance may serve several threads.
 *
 * @see <a href="https://minecraft.wiki/w/Region_file_format">Region file format</a>
 */
final class RegionFile implements AutoCloseable {
    private static final int SECTOR_SIZE = 4096;
    private static final int ENTRY_COUNT = 1024;
    private static final int HEADER_LENGTH = 2 * SECTOR_SIZE;

    // A chunk compound decodes into at most 64 MiB; adventure checks a declared array length before allocating it
    private static final BinaryTagIO.Reader TAG_READER = BinaryTagIO.reader(64L << 20);

    static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    private final Path path;
    private final FileChannel channel;
    private final long size;
    private final int[] locations = new int[ENTRY_COUNT];

    RegionFile(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            this.size = channel.size();
            // Vanilla leaves zero byte files behind for regions it opened and never wrote a chunk to
            if (size == 0) return;
            if (size < HEADER_LENGTH) throw new IOException(path + " is " + size + " bytes, shorter than the region header");
            final ByteBuffer header = read(0, SECTOR_SIZE);
            for (int i = 0; i < ENTRY_COUNT; i++) locations[i] = header.getInt();
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Whether the chunk has a location entry; chunk coordinates are taken modulo 32.
     */
    boolean hasChunk(int chunkX, int chunkZ) {
        return locations[index(chunkX, chunkZ)] != 0;
    }

    @Nullable CompoundBinaryTag readChunk(int chunkX, int chunkZ) throws IOException {
        final int location = locations[index(chunkX, chunkZ)];
        if (location == 0) return null;
        final long offset = (long) (location >>> 8) * SECTOR_SIZE;
        final int sectors = location & 0xFF;
        if (offset < HEADER_LENGTH || offset + 5 > size) {
            throw new IOException(where(chunkX, chunkZ) + " points at byte " + offset + ", outside of the file");
        }
        final ByteBuffer head = read(offset, 5);
        final int length = head.getInt();
        final int compression = head.get() & 0xFF;
        // +128 on the compression is a stub: vanilla wrote the chunk to c.<x>.<z>.mcc beside the region file
        if ((compression & 0x80) != 0) {
            throw new IOException(where(chunkX, chunkZ) + " is stored in its own c." + chunkX + "." + chunkZ
                    + ".mcc, which vanilla does for a chunk over 1 MiB compressed; make the chunk hold less"
                    + " (block entities, entities, item data) until vanilla saves it inside the region file");
        }
        // An unchecked length either allocates a negative array or an arbitrarily large one
        if (length < 1 || length > sectors * SECTOR_SIZE - 4 || offset + 4 + length > size) {
            throw new IOException(where(chunkX, chunkZ) + " declares a length of " + length
                    + ", outside of its " + sectors + " sector(s) or of the file");
        }
        final BinaryTagIO.Compression codec = switch (compression) {
            case 1 -> BinaryTagIO.Compression.GZIP;
            case 2 -> BinaryTagIO.Compression.ZLIB;
            case 3 -> BinaryTagIO.Compression.NONE;
            // server.properties region-file-compression=lz4; vanilla keeps a written chunk as it is when that changes
            case 4 -> throw new IOException(where(chunkX, chunkZ) + " is LZ4 compressed, only gzip, zlib and none are"
                    + " read; set region-file-compression=deflate and rewrite the save with the vanilla 26.2 server"
                    + " --forceUpgrade --recreateRegionFiles");
            default -> throw new IOException(where(chunkX, chunkZ) + " uses compression " + compression
                    + ", only gzip, zlib and none are read; save the source with region-file-compression=deflate");
        };
        final byte[] data = new byte[length - 1];
        readFully(ByteBuffer.wrap(data), offset + 5);
        try {
            return TAG_READER.read(new ByteArrayInputStream(data), codec);
        } catch (IOException | RuntimeException e) {
            // A damaged entry: a broken deflate stream, a truncated tag, an unknown tag id, a length over the limit
            throw new IOException(where(chunkX, chunkZ) + " does not decode: " + e, e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private ByteBuffer read(long position, int length) throws IOException {
        return readFully(ByteBuffer.allocate(length), position).flip();
    }

    private ByteBuffer readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException(path + " ends before byte " + (position + buffer.limit()));
            }
        }
        return buffer;
    }

    private String where(int chunkX, int chunkZ) {
        return "chunk " + chunkX + ", " + chunkZ + " of " + path;
    }

    private static int index(int chunkX, int chunkZ) {
        return (chunkZ & 31) * 32 + (chunkX & 31);
    }
}
