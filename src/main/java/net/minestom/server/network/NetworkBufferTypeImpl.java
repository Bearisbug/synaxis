package net.minestom.server.network;

import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.Nullable;

interface NetworkBufferTypeImpl<T> extends NetworkBuffer.Type<T> {
    int SEGMENT_BITS = 0x7F;
    int CONTINUE_BIT = 0x80;

    static <T> long sizeOf(NetworkBuffer.Type<T> type, T value, @Nullable Registries registries) {
        NetworkBuffer buffer = NetworkBufferImpl.dummy(registries);
        type.write(buffer, value);
        return buffer.writeIndex();
    }
}
