package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.Nullable;

interface NetworkBufferTypeImpl<T> extends NetworkBuffer.Type<T> {
    int SEGMENT_BITS = 0x7F;
    int CONTINUE_BIT = 0x80;
    /** Largest number of elements reserved before the first one is read, whatever length was declared. */
    int MAX_INITIAL_COLLECTION_SIZE = 65_536;

    /**
     * Returns how many elements to reserve for a collection whose declared length is {@code size}.
     *
     * <p>The declared length comes from the peer, so a few bytes can ask for an array of millions of
     * references. Reserving no more than the remaining input can back makes that allocation
     * proportional to what was actually sent; the caller grows the array as it reads. Element types
     * that encode to zero bytes still work because the reservation is at least one.</p>
     */
    static int initialCollectionSize(NetworkBuffer buffer, int size) {
        if (size == 0) return 0;
        final long inputBackedSize = Math.max(1, buffer.readableBytes());
        return (int) Math.min(size, Math.min(MAX_INITIAL_COLLECTION_SIZE, inputBackedSize));
    }

    /** Returns the length an array of {@code length} elements should grow to once full. */
    static int grownCollectionSize(int length, int size) {
        return (int) Math.min(size, (long) length * 2);
    }

    static <T> long sizeOf(NetworkBuffer.Type<T> type, T value, @Nullable Registries registries) {
        NetworkBuffer buffer = NetworkBufferImpl.dummy(registries);
        type.write(buffer, value);
        return buffer.writeIndex();
    }
}
