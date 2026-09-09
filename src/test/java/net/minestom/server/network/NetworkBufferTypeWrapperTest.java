package net.minestom.server.network;

import net.minestom.server.codec.Codec;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.Unit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkBufferTypeWrapperTest {

    @Test
    void lazyTypeRoundTrips() {
        final NetworkBuffer.Type<Integer> lazy = NetworkBuffer.Lazy(() -> NetworkBuffer.INT);
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);

        buffer.write(lazy, 1234);
        assertEquals(1234, buffer.read(lazy));
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void maxLengthKeepsTheBufferRegistries() {
        // TypedNBT needs registries, and maxLength must not measure the value without them
        final NetworkBuffer.Type<Unit> type = NetworkBuffer.TypedNBT(Codec.UNIT).maxLength(1024);
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64, Registries.vanilla());

        assertDoesNotThrow(() -> buffer.write(type, Unit.INSTANCE));
        assertEquals(Unit.INSTANCE, buffer.read(type));
    }
}
