package io.github.bearisbug.synaxis.network.packet.server.play.data;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE_ARRAY;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG_ARRAY;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link LightData} is also read from map files ({@code .synx} segment ①): its masks, its two array
 * lists and every array in them are bounded on read, and refused with
 * {@link IllegalArgumentException}.
 */
public class LightDataReadLimitsTest {

    /** Four masks of {@code maskLongs} longs, then two lists of {@code arrays} arrays of {@code arrayBytes} bytes. */
    private static NetworkBuffer lightData(int maskLongs, int arrays, int arrayBytes) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        for (int i = 0; i < 4; i++) buffer.write(LONG_ARRAY, new long[maskLongs]);
        for (int i = 0; i < 2; i++) buffer.write(BYTE_ARRAY.list(), Collections.nCopies(arrays, new byte[arrayBytes]));
        return buffer;
    }

    @Test
    void theLargestLightOfTheTallestWorldReadsBack() {
        final LightData light = lightData(4, LightData.MAX_SECTIONS, 2048).read(LightData.NETWORK_TYPE);
        assertEquals(LightData.MAX_SECTIONS, light.skyLight().size());
        assertEquals(LightData.MAX_SECTIONS, light.blockLight().size());
        assertEquals(List.of(2048), light.skyLight().stream().map(array -> array.length).distinct().toList());
    }

    @Test
    void aListOfMoreArraysThanSectionsIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> lightData(0, LightData.MAX_SECTIONS + 1, 0).read(LightData.NETWORK_TYPE));
    }

    @Test
    void anArrayOfMoreThan2048BytesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> lightData(0, 1, 2049).read(LightData.NETWORK_TYPE));
    }

    @Test
    void aMaskOfMoreThan256BitsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> lightData(5, 0, 0).read(LightData.NETWORK_TYPE));
    }

    @Test
    void aNegativeLengthIsRefused() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(VAR_INT, -1);
        assertThrows(IllegalArgumentException.class, () -> buffer.read(LightData.NETWORK_TYPE));
    }
}
