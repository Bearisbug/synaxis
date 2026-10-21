package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.Arrays;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.FixedRawBytes;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FixedRawLongs;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_LONGS;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_LONG;

final class NetworkBufferArrayTypes {

    record ByteArrayType(int maxLength) implements NetworkBufferTypeImpl<byte[]> {
        @Override
        public void write(NetworkBuffer buffer, byte[] value) {
            buffer.write(VAR_INT, value.length);
            buffer.write(RAW_BYTES, value);
        }

        @Override
        public byte[] read(NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            checkReadLength(length, maxLength);
            return buffer.read(FixedRawBytes(length));
        }
    }

    record LongArrayType(int maxLength) implements NetworkBufferTypeImpl<long[]> {
        @Override
        public void write(NetworkBuffer buffer, long[] value) {
            buffer.write(VAR_INT, value.length);
            buffer.write(RAW_LONGS, value);
        }

        @Override
        public long[] read(NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            checkReadLength(length, maxLength);
            return buffer.read(FixedRawLongs(length));
        }
    }

    // A negative length is refused by the fixed length type that reads the elements
    private static void checkReadLength(int length, int maxLength) {
        Check.argCondition(length > maxLength, "Array length ({0}) is over the maximum of {1}", length, maxLength);
    }

    record VarIntArrayType() implements NetworkBufferTypeImpl<int[]> {
        @Override
        public void write(NetworkBuffer buffer, int[] value) {
            buffer.write(VAR_INT, value.length);
            for (int i : value) buffer.write(VAR_INT, i);
        }

        @Override
        public int[] read(NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            Check.argCondition(length < 0, "Array length cannot be negative: {0}", length);
            int[] ints = new int[NetworkBufferTypeImpl.initialCollectionSize(buffer, length)];
            for (int i = 0; i < length; i++) {
                if (i == ints.length) {
                    ints = Arrays.copyOf(ints, NetworkBufferTypeImpl.grownCollectionSize(ints.length, length));
                }
                ints[i] = buffer.read(VAR_INT);
            }
            return ints;
        }
    }

    record VarLongArrayType() implements NetworkBufferTypeImpl<long[]> {
        @Override
        public void write(NetworkBuffer buffer, long[] value) {
            buffer.write(VAR_INT, value.length);
            for (long l : value) buffer.write(VAR_LONG, l);
        }

        @Override
        public long[] read(NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            Check.argCondition(length < 0, "Array length cannot be negative: {0}", length);
            long[] longs = new long[NetworkBufferTypeImpl.initialCollectionSize(buffer, length)];
            for (int i = 0; i < length; i++) {
                if (i == longs.length) {
                    longs = Arrays.copyOf(longs, NetworkBufferTypeImpl.grownCollectionSize(longs.length, length));
                }
                longs[i] = buffer.read(VAR_LONG);
            }
            return longs;
        }
    }
}
