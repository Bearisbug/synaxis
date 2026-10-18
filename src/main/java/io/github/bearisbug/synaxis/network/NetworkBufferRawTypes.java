package io.github.bearisbug.synaxis.network;

import static io.github.bearisbug.synaxis.network.NetworkBufferImpl.impl;

final class NetworkBufferRawTypes {

    record RawBytesType(int length) implements NetworkBufferTypeImpl<byte[]> {
        @Override
        public void write(NetworkBuffer buffer, byte[] value) {
            if (length != -1 && value.length != length) {
                throw new IllegalArgumentException("Invalid length: " + value.length + " != " + length);
            }
            final int count = value.length;
            if (count == 0) return;
            buffer.ensureWritable(count);
            impl(buffer)._putBytes(buffer.writeIndex(), value);
            buffer.advanceWrite(count);
        }

        @Override
        public byte[] read(NetworkBuffer buffer) {
            final long readable = buffer.readableBytes();
            final int count = length != -1 ? length : Math.toIntExact(readable);
            assert count >= 0 : "Invalid count: " + count;
            if (count > readable) // count == byteLength
                throw new IndexOutOfBoundsException("Buffer needs " + count + " bytes to read, found " + readable);
            final byte[] bytes = new byte[count];
            impl(buffer)._getBytes(buffer.readIndex(), bytes);
            buffer.advanceRead(count);
            return bytes;
        }
    }

    record RawShortsType(int length) implements NetworkBufferTypeImpl<short[]> {
        @Override
        public void write(NetworkBuffer buffer, short[] value) {
            if (length != -1 && value.length != length) {
                throw new IllegalArgumentException("Invalid length: " + value.length + " != " + length);
            }
            final long byteLength = (long) value.length * Short.BYTES;
            buffer.ensureWritable(byteLength);
            impl(buffer)._putShorts(buffer.writeIndex(), value);
            buffer.advanceWrite(byteLength);
        }

        @Override
        public short[] read(NetworkBuffer buffer) {
            final long readable = buffer.readableBytes();
            final int count = length != -1 ? length : Math.toIntExact(readable / Short.BYTES);
            assert count >= 0 : "Invalid count: " + count;
            final long byteLength = (long) count * Short.BYTES;
            if (byteLength > readable)
                throw new IndexOutOfBoundsException("Buffer needs " + byteLength + " bytes to read, found " + readable);
            final short[] values = new short[count];
            impl(buffer)._getShorts(buffer.readIndex(), values);
            buffer.advanceRead(byteLength);
            return values;
        }
    }

    record RawIntsType(int length) implements NetworkBufferTypeImpl<int[]> {
        @Override
        public void write(NetworkBuffer buffer, int[] value) {
            if (length != -1 && value.length != length) {
                throw new IllegalArgumentException("Invalid length: " + value.length + " != " + length);
            }
            final long byteLength = (long) value.length * Integer.BYTES;
            buffer.ensureWritable(byteLength);
            impl(buffer)._putInts(buffer.writeIndex(), value);
            buffer.advanceWrite(byteLength);
        }

        @Override
        public int[] read(NetworkBuffer buffer) {
            final long readable = buffer.readableBytes();
            final int count = length != -1 ? length : Math.toIntExact(readable / Integer.BYTES);
            assert count >= 0 : "Invalid count: " + count;
            final long byteLength = (long) count * Integer.BYTES;
            if (byteLength > readable)
                throw new IndexOutOfBoundsException("Buffer needs " + byteLength + " bytes to read, found " + readable);
            final int[] values = new int[count];
            impl(buffer)._getInts(buffer.readIndex(), values);
            buffer.advanceRead(byteLength);
            return values;
        }
    }

    record RawLongsType(int length) implements NetworkBufferTypeImpl<long[]> {
        @Override
        public void write(NetworkBuffer buffer, long[] value) {
            if (length != -1 && value.length != length) {
                throw new IllegalArgumentException("Invalid length: " + value.length + " != " + length);
            }
            final long byteLength = (long) value.length * Long.BYTES;
            buffer.ensureWritable(byteLength);
            impl(buffer)._putLongs(buffer.writeIndex(), value);
            buffer.advanceWrite(byteLength);
        }

        @Override
        public long[] read(NetworkBuffer buffer) {
            final long readable = buffer.readableBytes();
            final int count = length != -1 ? length : Math.toIntExact(readable / Long.BYTES);
            assert count >= 0 : "Invalid count: " + count;
            final long byteLength = (long) count * Long.BYTES;
            if (byteLength > readable)
                throw new IndexOutOfBoundsException("Buffer needs " + byteLength + " bytes to read, found " + readable);
            final long[] values = new long[count];
            impl(buffer)._getLongs(buffer.readIndex(), values);
            buffer.advanceRead(byteLength);
            return values;
        }
    }

    record RawFloatsType(int length) implements NetworkBufferTypeImpl<float[]> {
        @Override
        public void write(NetworkBuffer buffer, float[] value) {
            if (length != -1 && value.length != length) {
                throw new IllegalArgumentException("Invalid length: " + value.length + " != " + length);
            }
            final long byteLength = (long) value.length * Float.BYTES;
            buffer.ensureWritable(byteLength);
            impl(buffer)._putFloats(buffer.writeIndex(), value);
            buffer.advanceWrite(byteLength);
        }

        @Override
        public float[] read(NetworkBuffer buffer) {
            final long readable = buffer.readableBytes();
            final int count = length != -1 ? length : Math.toIntExact(readable / Float.BYTES);
            assert count >= 0 : "Invalid count: " + count;
            final long byteLength = (long) count * Float.BYTES;
            if (byteLength > readable)
                throw new IndexOutOfBoundsException("Buffer needs " + byteLength + " bytes to read, found " + readable);
            final float[] values = new float[count];
            impl(buffer)._getFloats(buffer.readIndex(), values);
            buffer.advanceRead(byteLength);
            return values;
        }
    }

    record RawDoublesType(int length) implements NetworkBufferTypeImpl<double[]> {
        @Override
        public void write(NetworkBuffer buffer, double[] value) {
            if (length != -1 && value.length != length) {
                throw new IllegalArgumentException("Invalid length: " + value.length + " != " + length);
            }
            final long byteLength = (long) value.length * Double.BYTES;
            buffer.ensureWritable(byteLength);
            impl(buffer)._putDoubles(buffer.writeIndex(), value);
            buffer.advanceWrite(byteLength);
        }

        @Override
        public double[] read(NetworkBuffer buffer) {
            final long readable = buffer.readableBytes();
            final int count = length != -1 ? length : Math.toIntExact(readable / Double.BYTES);
            assert count >= 0 : "Invalid count: " + count;
            final long byteLength = (long) count * Double.BYTES;
            if (byteLength > readable)
                throw new IndexOutOfBoundsException("Buffer needs " + byteLength + " bytes to read, found " + readable);
            final double[] values = new double[count];
            impl(buffer)._getDoubles(buffer.readIndex(), values);
            buffer.advanceRead(byteLength);
            return values;
        }
    }
}
