package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.coordinate.Direction;
import io.github.bearisbug.synaxis.utils.Unit;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** The parent only ever sees the declared bytes, so a short declared length bounds the work of the read. */
    @Test
    void aLengthPrefixedValueIsReadWithinItsDeclaredLength() {
        final AtomicLong consumed = new AtomicLong();
        final NetworkBuffer.Type<List<Integer>> counting = new NetworkBuffer.Type<>() {
            @Override
            public void write(NetworkBuffer buffer, List<Integer> value) {
                buffer.write(NetworkBuffer.VAR_INT.list(), value);
            }

            @Override
            public List<Integer> read(NetworkBuffer buffer) {
                final long start = buffer.readIndex();
                try {
                    return buffer.read(NetworkBuffer.VAR_INT.list());
                } finally {
                    consumed.set(buffer.readIndex() - start);
                }
            }
        };
        final byte[] list = NetworkBuffer.makeArray(NetworkBuffer.VAR_INT.list(), Collections.nCopies(1000, 1));
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(list.length + 8);
        buffer.write(NetworkBuffer.VAR_INT, 2);
        buffer.write(NetworkBuffer.RAW_BYTES, list);

        assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(counting.lengthPrefixed(16)));
        assertTrue(consumed.get() <= 2, () -> "the parent consumed " + consumed.get() + " bytes");
    }

    @Test
    void aNegativeDeclaredLengthIsRejectedBeforeTheParentReads() {
        final AtomicLong reads = new AtomicLong();
        final NetworkBuffer.Type<Integer> counting = NetworkBuffer.VAR_INT.transform(value -> {
            reads.incrementAndGet();
            return value;
        }, value -> value);
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, -1);
        buffer.write(NetworkBuffer.VAR_INT, 5);

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> buffer.read(counting.lengthPrefixed(16)));
        assertTrue(error.getMessage().contains("outside"), error::getMessage);
        assertEquals(0, reads.get());
    }

    @Test
    void aLengthPrefixedValueShorterThanDeclaredReportsBothLengths() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        buffer.write(NetworkBuffer.VAR_INT, 5);
        buffer.write(NetworkBuffer.INT, 7);
        buffer.write(NetworkBuffer.BYTE, (byte) 0);

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> buffer.read(NetworkBuffer.INT.lengthPrefixed(16)));
        assertTrue(error.getMessage().contains("declared: 5"), error::getMessage);
        assertTrue(error.getMessage().contains("read: 4"), error::getMessage);
    }

    @Test
    void aLengthPrefixedValueRoundTripsAndIsLimitedWhenWritten() {
        final NetworkBuffer.Type<String> type = NetworkBuffer.STRING.lengthPrefixed(4);
        final byte[] bytes = NetworkBuffer.makeArray(type, "abc");
        assertArrayEquals(new byte[]{4, 3, 'a', 'b', 'c'}, bytes);
        final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length);
        assertEquals("abc", buffer.read(type));
        assertEquals(0, buffer.readableBytes());

        assertThrows(IllegalArgumentException.class, () -> NetworkBuffer.makeArray(type, "abcd"));
    }

    @Test
    void anOptionalVarIntIsTheValuePlusOneWithoutABooleanPrefix() {
        assertArrayEquals(new byte[]{0}, NetworkBuffer.makeArray(NetworkBuffer.OPTIONAL_VAR_INT, null));
        assertArrayEquals(new byte[]{1}, NetworkBuffer.makeArray(NetworkBuffer.OPTIONAL_VAR_INT, 0));
        // -1 encodes the same as null and so reads back as null
        final byte[] minusOne = NetworkBuffer.makeArray(NetworkBuffer.OPTIONAL_VAR_INT, -1);
        assertArrayEquals(new byte[]{0}, minusOne);
        assertNull(NetworkBuffer.wrap(minusOne, 0, minusOne.length).read(NetworkBuffer.OPTIONAL_VAR_INT));
        final byte[] large = NetworkBuffer.makeArray(NetworkBuffer.OPTIONAL_VAR_INT, Integer.MAX_VALUE - 1);
        assertEquals(Integer.MAX_VALUE - 1, NetworkBuffer.wrap(large, 0, large.length).read(NetworkBuffer.OPTIONAL_VAR_INT));
    }

    @Test
    void varLongsRoundTripInTheirVanillaLength() {
        final long[] values = {Long.MIN_VALUE, -1L, 1L << 35};
        final int[] lengths = {10, 10, 6};
        for (int i = 0; i < values.length; i++) {
            final byte[] bytes = NetworkBuffer.makeArray(NetworkBuffer.VAR_LONG, values[i]);
            assertEquals(lengths[i], bytes.length, "length of " + values[i]);
            assertEquals(values[i], NetworkBuffer.wrap(bytes, 0, bytes.length).read(NetworkBuffer.VAR_LONG));
        }
    }

    @Test
    void fixedBitSetsTakeOneByteForEveryEightBits() {
        final BitSet bits = new BitSet();
        bits.set(8);
        assertEquals(2, NetworkBuffer.makeArray(NetworkBuffer.FixedBitSet(9), bits).length);
        final byte[] eight = NetworkBuffer.makeArray(NetworkBuffer.FixedBitSet(8), new BitSet());
        assertEquals(1, eight.length);
        final byte[] nine = NetworkBuffer.makeArray(NetworkBuffer.FixedBitSet(9), bits);
        assertEquals(bits, NetworkBuffer.wrap(nine, 0, nine.length).read(NetworkBuffer.FixedBitSet(9)));
        assertFalse(NetworkBuffer.wrap(eight, 0, eight.length).read(NetworkBuffer.FixedBitSet(8)).get(0));
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
