package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.utils.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declared collection length is peer controlled, so it must never size an allocation on its own.
 * The reads below all declare far more elements than they send and must fail on the missing input
 * rather than on the reservation.
 */
class NetworkBufferCollectionTypesTest {

    /** Reserving this many references up front would need 8 GiB, so an allocation attempt fails the test. */
    private static final int IMPOSSIBLE_SIZE = Integer.MAX_VALUE;

    private static NetworkBuffer declaring(int size, int payloadBytes) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(VAR_INT, size);
        buffer.write(RAW_BYTES, new byte[payloadBytes]);
        return buffer;
    }

    @Test
    void listDeclaringMoreElementsThanSentRunsOutOfInput() {
        final NetworkBuffer buffer = declaring(IMPOSSIBLE_SIZE, 10);
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(INT.list()));
    }

    @Test
    void setDeclaringMoreElementsThanSentRunsOutOfInput() {
        final NetworkBuffer buffer = declaring(IMPOSSIBLE_SIZE, 10);
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(INT.set()));
    }

    @Test
    void mapDeclaringMoreEntriesThanSentRunsOutOfInput() {
        final NetworkBuffer buffer = declaring(IMPOSSIBLE_SIZE, 10);
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(INT.mapValue(INT)));
    }

    @Test
    void varIntArrayDeclaringMoreElementsThanSentRunsOutOfInput() {
        final NetworkBuffer buffer = declaring(IMPOSSIBLE_SIZE, 10);
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(NetworkBuffer.VAR_INT_ARRAY));
    }

    @Test
    void negativeArrayLengthIsRejected() {
        final NetworkBuffer buffer = declaring(-1, 10);
        assertThrows(IllegalArgumentException.class, () -> buffer.read(NetworkBuffer.VAR_INT_ARRAY));
    }

    @Test
    void initialSizeIsBoundedByTheRemainingInput() {
        final NetworkBuffer buffer = declaring(IMPOSSIBLE_SIZE, 10);
        buffer.read(VAR_INT);
        assertEquals(10, NetworkBufferTypeImpl.initialCollectionSize(buffer, IMPOSSIBLE_SIZE));
        assertEquals(4, NetworkBufferTypeImpl.initialCollectionSize(buffer, 4));
        assertEquals(0, NetworkBufferTypeImpl.initialCollectionSize(buffer, 0));
    }

    /** Growth has to keep working past the reservation cap, so a legitimate long collection still reads. */
    @Test
    void collectionsGrowPastTheInitialReservationCap() {
        final int size = NetworkBufferTypeImpl.MAX_INITIAL_COLLECTION_SIZE + 1;
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(VAR_INT, size);
        for (int i = 0; i < size; i++) buffer.write(INT, i);

        final List<Integer> values = buffer.read(INT.list());
        assertEquals(size, values.size());
        assertEquals(size - 1, values.getLast());

        // A set reads its elements the same way, and every slot must be filled for Set.of
        buffer.readIndex(0);
        final Set<Integer> set = buffer.read(INT.set());
        assertEquals(size, set.size());
        assertTrue(set.contains(size - 1));
    }

    @Test
    void mapsGrowPastTheInitialReservationCap() {
        final int size = NetworkBufferTypeImpl.MAX_INITIAL_COLLECTION_SIZE + 1;
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(VAR_INT, size);
        for (int i = 0; i < size; i++) {
            buffer.write(INT, i);
            buffer.write(INT, -i);
        }

        final Map<Integer, Integer> map = buffer.read(INT.mapValue(INT));
        assertEquals(size, map.size());
        assertEquals(-(size - 1), map.get(size - 1));
    }

    /** A zero byte element type still reads, the reservation is never allowed to reach zero. */
    @Test
    void zeroByteElementsStillRead() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(VAR_INT, 3);
        final List<Unit> values = buffer.read(NetworkBuffer.UNIT.list());
        assertEquals(3, values.size());
    }

    /** A null list or set is written as an empty one, a var-int length of zero, and reads back empty. */
    @Test
    void aNullListOrSetIsWrittenAsAnEmptyOne() {
        final byte[] emptyLength = NetworkBuffer.makeArray(VAR_INT, 0);
        final byte[] list = NetworkBuffer.makeArray(buffer -> buffer.write(INT.list(), null));
        final byte[] set = NetworkBuffer.makeArray(buffer -> buffer.write(INT.set(), null));

        assertArrayEquals(emptyLength, list);
        assertArrayEquals(emptyLength, set);
        assertEquals(List.of(), NetworkBuffer.wrap(list, 0, list.length).read(INT.list()));
        assertEquals(Set.of(), NetworkBuffer.wrap(set, 0, set.length).read(INT.set()));
    }
}
