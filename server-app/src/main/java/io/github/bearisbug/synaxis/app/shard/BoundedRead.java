package io.github.bearisbug.synaxis.app.shard;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;

/**
 * Reads a file the shard takes whole, the manifest and {@code shards.json}, on the one channel it
 * compares the size on ({@code docs/DESIGN-M3.md} {@code API-M3-002} 只读一次): looking the size up by the
 * path and reading by the path again would let a file swapped in between pass the cap.
 */
final class BoundedRead {

    private BoundedRead() {
    }

    /**
     * The bytes of the channel, unless there are more than {@code max}: its size first, before a byte is
     * read, then at most {@code max + 1} bytes, in case it grew since. A file that grows while it is read
     * may come back cut short.
     *
     * @return the bytes, null when the channel holds more than {@code max}
     * @throws IOException if it cannot be read
     */
    static byte @Nullable [] readAtMost(SeekableByteChannel channel, int max) throws IOException {
        if (channel.size() > max) return null;
        // Not closed: the caller owns the channel
        final byte[] bytes = Channels.newInputStream(channel).readNBytes(max + 1);
        return bytes.length > max ? null : bytes;
    }
}
