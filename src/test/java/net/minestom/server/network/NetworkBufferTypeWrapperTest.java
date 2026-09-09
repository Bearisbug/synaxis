package net.minestom.server.network;

import org.junit.jupiter.api.Test;

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
}
