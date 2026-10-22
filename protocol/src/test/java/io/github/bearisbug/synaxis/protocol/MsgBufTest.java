package io.github.bearisbug.synaxis.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The hand written primitives of {@code API-M2-001}: round trips plus the malformed inputs of
 * {@code docs/TEST.md} TC-GAME-018, which must all end in a {@link MalformedMessageException}
 * instead of an index out of bounds or an allocation sized by the sender.
 */
class MsgBufTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 127, 128, 255, 16_384, Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
    void aVarIntRoundTrips(int value) {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeVarInt(value);
        assertEquals(value, MsgBuf.reading(writer.toByteArray()).readVarInt());
    }

    @Test
    void aVarIntUsesTheSevenBitLayout() {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeVarInt(300);
        assertArrayEquals(new byte[]{(byte) 0xAC, 0x02}, writer.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 127L, 128L, Long.MAX_VALUE, -1L, Long.MIN_VALUE})
    void aVarLongRoundTrips(long value) {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeVarLong(value);
        assertEquals(value, MsgBuf.reading(writer.toByteArray()).readVarLong());
    }

    @ParameterizedTest
    @ValueSource(floats = {0f, -0.5f, 128.25f, -4096f, Float.MAX_VALUE})
    void aFloatRoundTrips(float value) {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeFloat(value);
        assertEquals(value, MsgBuf.reading(writer.toByteArray()).readFloat());
    }

    @Test
    void aStringRoundTripsAsUtf8() {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeString("synaxis:play 中文");
        final MsgBuf reader = MsgBuf.reading(writer.toByteArray());
        assertEquals("synaxis:play 中文", reader.readString(256));
        assertEquals(0, reader.remaining());
    }

    @Test
    void aFixedArrayRoundTrips() {
        final byte[] value = {1, 2, 3, 4};
        final MsgBuf writer = MsgBuf.writing();
        writer.writeBytes(value);
        assertArrayEquals(value, MsgBuf.reading(writer.toByteArray()).readBytes(value.length));
    }

    @Test
    void aVarIntListRoundTrips() {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeVarIntList(List.of(1, 2, 300));
        assertEquals(List.of(1, 2, 300), MsgBuf.reading(writer.toByteArray()).readVarIntList(8));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 255})
    void anUnsignedByteRoundTrips(int value) {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeUnsignedByte(value);
        assertEquals(value, MsgBuf.reading(writer.toByteArray()).readUnsignedByte());
    }

    @Test
    void theBufferGrowsPastItsInitialCapacity() {
        final MsgBuf writer = MsgBuf.writing();
        for (int i = 0; i < 1_000; i++) writer.writeVarInt(i);
        final MsgBuf reader = MsgBuf.reading(writer.toByteArray());
        for (int i = 0; i < 1_000; i++) assertEquals(i, reader.readVarInt());
        assertEquals(0, reader.remaining());
    }

    @Test
    void aTruncatedVarIntIsRejected() {
        final MsgBuf reader = MsgBuf.reading(new byte[]{(byte) 0x80});
        assertThrows(MalformedMessageException.class, reader::readVarInt);
    }

    @Test
    void anOverlongVarIntIsRejected() {
        final byte[] overlong = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(overlong).readVarInt());
    }

    @Test
    void anOverlongVarLongIsRejected() {
        final byte[] overlong = new byte[11];
        for (int i = 0; i < overlong.length; i++) overlong[i] = (byte) 0x80;
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(overlong).readVarLong());
    }

    @Test
    void readingPastTheEndIsRejected() {
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(new byte[]{1, 2}).readFloat());
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(new byte[0]).readUnsignedByte());
    }

    @Test
    void aNonFiniteFloatIsRejectedOnBothSides() {
        final MsgBuf nan = MsgBuf.reading(new byte[]{0x7F, (byte) 0xC0, 0, 0});
        assertThrows(MalformedMessageException.class, nan::readFloat);
        assertThrows(IllegalArgumentException.class, () -> MsgBuf.writing().writeFloat(Float.POSITIVE_INFINITY));
    }

    @Test
    void aStringOverItsBudgetIsRejected() {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeString("abcdefghij");
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(writer.toByteArray()).readString(4));
    }

    @Test
    void aNegativeLengthIsRejected() {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeVarInt(-1);
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(writer.toByteArray()).readString(256));
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(writer.toByteArray()).readCount(8));
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(new byte[0]).readBytes(-1));
    }

    @Test
    void aCountLargerThanThePayloadIsRejectedBeforeAllocating() {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeVarInt(Integer.MAX_VALUE);
        assertThrows(MalformedMessageException.class,
                () -> MsgBuf.reading(writer.toByteArray()).readCount(Integer.MAX_VALUE));
    }

    @Test
    void aCountOverItsCapIsRejected() {
        final MsgBuf writer = MsgBuf.writing();
        writer.writeVarInt(9);
        writer.writeBytes(new byte[9]);
        assertThrows(MalformedMessageException.class, () -> MsgBuf.reading(writer.toByteArray()).readCount(8));
    }

    @Test
    void anUnsignedByteOutsideItsRangeIsRejectedOnWrite() {
        assertThrows(IllegalArgumentException.class, () -> MsgBuf.writing().writeUnsignedByte(256));
        assertThrows(IllegalArgumentException.class, () -> MsgBuf.writing().writeUnsignedByte(-1));
    }
}
