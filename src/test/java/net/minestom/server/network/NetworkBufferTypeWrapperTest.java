package net.minestom.server.network;

import net.minestom.server.codec.Codec;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.Direction;
import net.minestom.server.utils.Unit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void negativeCollectionSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> sizedBuffer().read(NetworkBuffer.VAR_INT.list(4)));
        assertThrows(IllegalArgumentException.class, () -> sizedBuffer().read(NetworkBuffer.VAR_INT.set(4)));
        assertThrows(IllegalArgumentException.class,
                () -> sizedBuffer().read(NetworkBuffer.VAR_INT.mapValue(NetworkBuffer.VAR_INT, 4)));
    }

    @Test
    void terminatedStringRoundTrips() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.STRING_TERMINATED, "abc");
        assertEquals("abc", buffer.read(NetworkBuffer.STRING_TERMINATED));
    }

    @Test
    void enumTypeRejectsOutOfRangeOrdinals() {
        final NetworkBuffer.Type<Direction> type = NetworkBuffer.Enum(Direction.class);
        assertThrows(IllegalArgumentException.class, () -> ordinalBuffer(-1).read(type));
        assertThrows(IllegalArgumentException.class, () -> ordinalBuffer(99).read(type));
    }

    private static NetworkBuffer ordinalBuffer(int ordinal) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, ordinal);
        return buffer;
    }

    private static NetworkBuffer sizedBuffer() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, -1);
        return buffer;
    }
}
