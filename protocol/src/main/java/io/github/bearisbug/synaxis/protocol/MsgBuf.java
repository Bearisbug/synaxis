package io.github.bearisbug.synaxis.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The hand written wire primitives every message is built from, see {@code docs/DESIGN-M2.md} §8
 * {@code API-M2-001}.
 * <p>
 * A buffer is either reading ({@link #reading(byte[])}) or writing ({@link #writing()}); the write
 * side grows on demand, the read side never grows. Numbers are big endian and VarInt / VarLong use
 * the seven bit continuation layout of the vanilla protocol.
 * <p>
 * <b>Every read is bounded.</b> A payload is whatever the peer sent, so a truncated field, an
 * overlong VarInt and a length that does not fit in the remaining bytes all raise
 * {@link MalformedMessageException} rather than an index out of bounds — and no read allocates an
 * array sized by the sender without first checking it against the bytes actually left (see
 * {@link #readCount(int)}).
 */
public final class MsgBuf {

    private static final int INITIAL_CAPACITY = 64;
    private static final int MAX_VAR_INT_BYTES = 5;
    private static final int MAX_VAR_LONG_BYTES = 10;

    private ByteBuffer buffer;

    private MsgBuf(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Wraps a received payload for decoding; the array is not copied.
     *
     * @param bytes the payload as it arrived on the wire
     * @return a reading buffer positioned at the first byte
     */
    public static MsgBuf reading(byte[] bytes) {
        return new MsgBuf(ByteBuffer.wrap(bytes));
    }

    /**
     * Opens an empty buffer for encoding one message.
     *
     * @return a writing buffer that grows as it is written to
     */
    public static MsgBuf writing() {
        return new MsgBuf(ByteBuffer.allocate(INITIAL_CAPACITY));
    }

    /**
     * How many bytes are still unread, which is also the upper bound of any length a peer can
     * legitimately declare. On a writing buffer this number has no meaning.
     *
     * @return the unread byte count
     */
    public int remaining() {
        return buffer.remaining();
    }

    /**
     * Takes the encoded message out of a writing buffer.
     *
     * @return a copy of the bytes written so far
     */
    public byte[] toByteArray() {
        final byte[] bytes = new byte[buffer.position()];
        buffer.get(0, bytes);
        return bytes;
    }

    /**
     * Writes a VarInt.
     *
     * @param value any {@code int}; negative values take the full five bytes
     */
    public void writeVarInt(int value) {
        ensureWritable(MAX_VAR_INT_BYTES);
        int rest = value;
        while ((rest & ~0x7F) != 0) {
            buffer.put((byte) ((rest & 0x7F) | 0x80));
            rest >>>= 7;
        }
        buffer.put((byte) rest);
    }

    /**
     * Reads a VarInt.
     *
     * @return the decoded value
     * @throws MalformedMessageException if the field is truncated or longer than five bytes
     */
    public int readVarInt() {
        int value = 0;
        for (int i = 0; i < MAX_VAR_INT_BYTES; i++) {
            final int read = readRawByte();
            value |= (read & 0x7F) << (i * 7);
            if ((read & 0x80) == 0) return value;
        }
        throw new MalformedMessageException("VarInt is longer than " + MAX_VAR_INT_BYTES + " bytes");
    }

    /**
     * Writes a VarLong.
     *
     * @param value any {@code long}; negative values take the full ten bytes
     */
    public void writeVarLong(long value) {
        ensureWritable(MAX_VAR_LONG_BYTES);
        long rest = value;
        while ((rest & ~0x7FL) != 0) {
            buffer.put((byte) ((rest & 0x7FL) | 0x80L));
            rest >>>= 7;
        }
        buffer.put((byte) rest);
    }

    /**
     * Reads a VarLong.
     *
     * @return the decoded value
     * @throws MalformedMessageException if the field is truncated or longer than ten bytes
     */
    public long readVarLong() {
        long value = 0;
        for (int i = 0; i < MAX_VAR_LONG_BYTES; i++) {
            final int read = readRawByte();
            value |= (long) (read & 0x7F) << (i * 7);
            if ((read & 0x80) == 0) return value;
        }
        throw new MalformedMessageException("VarLong is longer than " + MAX_VAR_LONG_BYTES + " bytes");
    }

    /**
     * Writes an IEEE 754 single precision float.
     *
     * @param value a finite value; the wire has no field where NaN or an infinity means anything
     * @throws IllegalArgumentException if the value is not finite
     */
    public void writeFloat(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("f32 field is not finite: " + value);
        ensureWritable(Float.BYTES);
        buffer.putFloat(value);
    }

    /**
     * Reads an IEEE 754 single precision float.
     *
     * @return the decoded value
     * @throws MalformedMessageException if the field is truncated or not finite
     */
    public float readFloat() {
        require(Float.BYTES);
        final float value = buffer.getFloat();
        if (!Float.isFinite(value)) throw new MalformedMessageException("f32 field is not finite: " + value);
        return value;
    }

    /**
     * Writes one unsigned byte.
     *
     * @param value a value in {@code [0, 255]}
     * @throws IllegalArgumentException if the value does not fit in an unsigned byte
     */
    public void writeUnsignedByte(int value) {
        if ((value & ~0xFF) != 0) throw new IllegalArgumentException("u8 field is outside [0, 255]: " + value);
        ensureWritable(1);
        buffer.put((byte) value);
    }

    /**
     * Reads one unsigned byte.
     *
     * @return the value in {@code [0, 255]}
     * @throws MalformedMessageException if the buffer is exhausted
     */
    public int readUnsignedByte() {
        return readRawByte();
    }

    /**
     * Writes a UTF-8 string prefixed with its byte length.
     *
     * @param value the string to encode
     */
    public void writeString(String value) {
        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(utf8.length);
        writeBytes(utf8);
    }

    /**
     * Reads a UTF-8 string prefixed with its byte length.
     *
     * @param maxBytes the largest encoded length this field may declare
     * @return the decoded string
     * @throws MalformedMessageException if the declared length is negative, over the budget or
     *                                   longer than what is left in the buffer
     */
    public String readString(int maxBytes) {
        final int length = readVarInt();
        if (length < 0 || length > maxBytes) {
            throw new MalformedMessageException("string length %d is outside [0, %d]".formatted(length, maxBytes));
        }
        return new String(readBytes(length), StandardCharsets.UTF_8);
    }

    /**
     * Writes a fixed size array; the length is not part of the encoding and both ends must know it.
     *
     * @param value the bytes to append
     */
    public void writeBytes(byte[] value) {
        ensureWritable(value.length);
        buffer.put(value);
    }

    /**
     * Reads a fixed size array.
     *
     * @param length how many bytes to read
     * @return the bytes read
     * @throws MalformedMessageException if the length is negative or past the end of the buffer
     */
    public byte[] readBytes(int length) {
        if (length < 0) throw new MalformedMessageException("negative length: " + length);
        require(length);
        final byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Reads the {@code count} prefix of a list and checks it against both its own cap and the bytes
     * left, so that a peer cannot make the decoder allocate for entries it never sent.
     *
     * @param maxCount the largest count this field may declare, per {@code docs/DESIGN-M2.md} §9
     * @return the entry count
     * @throws MalformedMessageException if the count is negative, over the cap, or larger than the
     *                                   remaining bytes (every entry is at least one byte)
     */
    public int readCount(int maxCount) {
        final int count = readVarInt();
        if (count < 0 || count > maxCount) {
            throw new MalformedMessageException("count %d is outside [0, %d]".formatted(count, maxCount));
        }
        if (count > remaining()) {
            throw new MalformedMessageException(
                    "count %d does not fit in the %d bytes left".formatted(count, remaining()));
        }
        return count;
    }

    /**
     * Writes a VarInt list as a {@code count} prefix followed by the values.
     *
     * @param values the values to encode
     */
    public void writeVarIntList(List<Integer> values) {
        writeVarInt(values.size());
        for (final int value : values) writeVarInt(value);
    }

    /**
     * Reads a VarInt list written by {@link #writeVarIntList(List)}.
     *
     * @param maxCount the largest count this field may declare
     * @return the decoded values
     * @throws MalformedMessageException if the count fails {@link #readCount(int)} or the list is
     *                                   truncated
     */
    public List<Integer> readVarIntList(int maxCount) {
        final int count = readCount(maxCount);
        final List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readVarInt());
        return List.copyOf(values);
    }

    private int readRawByte() {
        require(1);
        return buffer.get() & 0xFF;
    }

    private void require(int bytes) {
        if (buffer.remaining() < bytes) {
            throw new MalformedMessageException(
                    "message is truncated: %d bytes needed, %d left".formatted(bytes, buffer.remaining()));
        }
    }

    private void ensureWritable(int bytes) {
        if (buffer.remaining() >= bytes) return;
        final int required = buffer.position() + bytes;
        int capacity = buffer.capacity();
        while (capacity < required) capacity *= 2;
        final ByteBuffer grown = ByteBuffer.allocate(capacity);
        grown.put(0, buffer, 0, buffer.position());
        grown.position(buffer.position());
        buffer = grown;
    }
}
