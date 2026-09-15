package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static io.github.bearisbug.synaxis.network.NetworkBufferImpl.impl;

final class NetworkBufferVarNumberTypes {

    record VarIntType() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(NetworkBuffer buffer, Integer boxed) {
            buffer.ensureWritable(5);
            long index = buffer.writeIndex();
            int value = boxed;
            var nio = impl(buffer);
            while (true) {
                if ((value & ~SEGMENT_BITS) == 0) {
                    nio._putByte(index++, (byte) value);
                    buffer.advanceWrite(index - buffer.writeIndex());
                    return;
                }
                nio._putByte(index++, (byte) ((byte) (value & SEGMENT_BITS) | CONTINUE_BIT));
                // Note: >>> means that the sign bit is shifted with the rest of the number rather than being left alone
                value >>>= 7;
            }
        }

        @Override
        public Integer read(NetworkBuffer buffer) {
            long index = buffer.readIndex();
            // https://github.com/jvm-profiling-tools/async-profiler/blob/a38a375dc62b31a8109f3af97366a307abb0fe6f/src/converter/one/jfr/JfrReader.java#L393
            int result = 0;
            for (int shift = 0; shift <= 28; shift += 7) {
                byte b = impl(buffer)._getByte(index++);
                result |= (b & 0x7f) << shift;
                if (b >= 0) {
                    buffer.advanceRead(index - buffer.readIndex());
                    return result;
                }
            }
            throw new IndexOutOfBoundsException("VarInt too long");
        }
    }

    record OptionalVarIntType() implements NetworkBufferTypeImpl<@Nullable Integer> {
        @Override
        public void write(NetworkBuffer buffer, @Nullable Integer value) {
            buffer.write(VAR_INT, value == null ? 0 : value + 1);
        }

        @Override
        public @Nullable Integer read(NetworkBuffer buffer) {
            final int value = buffer.read(VAR_INT);
            return value == 0 ? null : value - 1;
        }
    }

    record VarInt3Type() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(NetworkBuffer buffer, Integer boxed) {
            final int value = boxed;
            // Value must be between 0 and 2^21
            Check.argCondition(value < 0 || value >= (1 << 21), "VarInt3 out of bounds: {0}", value);
            buffer.ensureWritable(3);
            final long startIndex = buffer.writeIndex();
            var impl = impl(buffer);
            impl._putByte(startIndex, (byte) ((value & 0x7F) | 0x80));
            impl._putByte(startIndex + 1, (byte) (((value >>> 7) & 0x7F) | 0x80));
            impl._putByte(startIndex + 2, (byte) (value >>> 14));
            buffer.advanceWrite(3);
        }

        @Override
        public Integer read(NetworkBuffer buffer) {
            // Ensure that the buffer can read other var-int sizes
            // The optimization is mostly relevant for writing
            return buffer.read(VAR_INT);
        }
    }

    record VarLongType() implements NetworkBufferTypeImpl<Long> {
        @Override
        public void write(NetworkBuffer buffer, Long value) {
            buffer.ensureWritable(10);
            int size = 0;
            while (true) {
                if ((value & ~((long) SEGMENT_BITS)) == 0) {
                    impl(buffer)._putByte(buffer.writeIndex() + size, (byte) value.intValue());
                    buffer.advanceWrite(size + 1);
                    return;
                }
                impl(buffer)._putByte(buffer.writeIndex() + size, (byte) ((value & SEGMENT_BITS) | CONTINUE_BIT));
                size++;
                // Note: >>> means that the sign bit is shifted with the rest of the number rather than being left alone
                value >>>= 7;
            }
        }

        @Override
        public Long read(NetworkBuffer buffer) {
            int length = 0;
            long value = 0;
            int position = 0;
            byte currentByte;
            while (true) {
                currentByte = impl(buffer)._getByte(buffer.readIndex() + length);
                length++;
                value |= (long) (currentByte & SEGMENT_BITS) << position;
                if ((currentByte & CONTINUE_BIT) == 0) break;
                position += 7;
                if (position >= 64) throw new IndexOutOfBoundsException("VarLong is too big");
            }
            buffer.advanceRead(length);
            return value;
        }
    }
}
