package io.github.bearisbug.synaxis.app.shard;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * Reads a file the shard takes whole, the manifest and {@code shards.json}, on the one channel it
 * compares the size on ({@code docs/DESIGN-M3.md} {@code API-M3-002} 只读一次): looking the size up by the
 * path and reading by the path again would let a file swapped in between pass the cap.
 */
final class BoundedRead {
    private static final int CHUNK = 64 * 1024;

    private BoundedRead() {
    }

    /**
     * The bytes of the channel, unless there are more than {@code max}: its size first, before a byte is
     * read, then at most {@code max + 1} bytes, in case it grew since.
     *
     * @return the bytes, null when the channel holds more than {@code max}
     * @throws IOException if it cannot be read
     */
    static byte @Nullable [] readAtMost(SeekableByteChannel channel, int max) throws IOException {
        if (channel.size() > max) return null;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) channel.size());
        final ByteBuffer chunk = ByteBuffer.allocate(CHUNK);
        while (true) {
            chunk.clear().limit((int) Math.min(CHUNK, (long) max + 1 - bytes.size()));
            if (channel.read(chunk) < 0) return bytes.toByteArray();
            bytes.write(chunk.array(), 0, chunk.position());
            if (bytes.size() > max) return null;
        }
    }
}
