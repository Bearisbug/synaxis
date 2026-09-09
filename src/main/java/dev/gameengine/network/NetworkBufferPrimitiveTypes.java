package dev.gameengine.network;

import dev.gameengine.utils.Unit;

import java.util.UUID;

import static dev.gameengine.network.NetworkBuffer.LONG;
import static dev.gameengine.network.NetworkBufferImpl.impl;

final class NetworkBufferPrimitiveTypes {

    record UnitType() implements NetworkBufferTypeImpl<Unit> {
        @Override
        public void write(NetworkBuffer buffer, Unit value) {
        }

        @Override
        public Unit read(NetworkBuffer buffer) {
            return Unit.INSTANCE;
        }
    }

    record BooleanType() implements NetworkBufferTypeImpl<Boolean> {
        @Override
        public void write(NetworkBuffer buffer, Boolean value) {
            buffer.ensureWritable(1);
            impl(buffer)._putByte(buffer.writeIndex(), value ? (byte) 1 : (byte) 0);
            buffer.advanceWrite(1);
        }

        @Override
        public Boolean read(NetworkBuffer buffer) {
            final byte value = impl(buffer)._getByte(buffer.readIndex());
            buffer.advanceRead(1);
            return value == 1;
        }
    }

    record ByteType() implements NetworkBufferTypeImpl<Byte> {
        @Override
        public void write(NetworkBuffer buffer, Byte value) {
            buffer.ensureWritable(1);
            impl(buffer)._putByte(buffer.writeIndex(), value);
            buffer.advanceWrite(1);
        }

        @Override
        public Byte read(NetworkBuffer buffer) {
            final byte value = impl(buffer)._getByte(buffer.readIndex());
            buffer.advanceRead(1);
            return value;
        }
    }

    record UnsignedByteType() implements NetworkBufferTypeImpl<Short> {
        @Override
        public void write(NetworkBuffer buffer, Short value) {
            buffer.ensureWritable(1);
            impl(buffer)._putByte(buffer.writeIndex(), (byte) (value & 0xFF));
            buffer.advanceWrite(1);
        }

        @Override
        public Short read(NetworkBuffer buffer) {
            final byte value = impl(buffer)._getByte(buffer.readIndex());
            buffer.advanceRead(1);
            return (short) (value & 0xFF);
        }
    }

    record ShortType() implements NetworkBufferTypeImpl<Short> {
        @Override
        public void write(NetworkBuffer buffer, Short value) {
            buffer.ensureWritable(2);
            impl(buffer)._putShort(buffer.writeIndex(), value);
            buffer.advanceWrite(2);
        }

        @Override
        public Short read(NetworkBuffer buffer) {
            final short value = impl(buffer)._getShort(buffer.readIndex());
            buffer.advanceRead(2);
            return value;
        }
    }

    record UnsignedShortType() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(NetworkBuffer buffer, Integer value) {
            buffer.ensureWritable(2);
            impl(buffer)._putShort(buffer.writeIndex(), (short) (value & 0xFFFF));
            buffer.advanceWrite(2);
        }

        @Override
        public Integer read(NetworkBuffer buffer) {
            final short value = impl(buffer)._getShort(buffer.readIndex());
            buffer.advanceRead(2);
            return value & 0xFFFF;
        }
    }

    record IntType() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(NetworkBuffer buffer, Integer value) {
            buffer.ensureWritable(4);
            impl(buffer)._putInt(buffer.writeIndex(), value);
            buffer.advanceWrite(4);
        }

        @Override
        public Integer read(NetworkBuffer buffer) {
            final int value = impl(buffer)._getInt(buffer.readIndex());
            buffer.advanceRead(4);
            return value;
        }
    }

    record UnsignedIntType() implements NetworkBufferTypeImpl<Long> {
        @Override
        public void write(NetworkBuffer buffer, Long value) {
            buffer.ensureWritable(4);
            impl(buffer)._putInt(buffer.writeIndex(), (int) (value & 0xFFFFFFFFL));
            buffer.advanceWrite(4);
        }

        @Override
        public Long read(NetworkBuffer buffer) {
            final int value = impl(buffer)._getInt(buffer.readIndex());
            buffer.advanceRead(4);
            return value & 0xFFFFFFFFL;
        }
    }

    record LongType() implements NetworkBufferTypeImpl<Long> {
        @Override
        public void write(NetworkBuffer buffer, Long value) {
            buffer.ensureWritable(8);
            impl(buffer)._putLong(buffer.writeIndex(), value);
            buffer.advanceWrite(8);
        }

        @Override
        public Long read(NetworkBuffer buffer) {
            final long value = impl(buffer)._getLong(buffer.readIndex());
            buffer.advanceRead(8);
            return value;
        }
    }

    record FloatType() implements NetworkBufferTypeImpl<Float> {
        @Override
        public void write(NetworkBuffer buffer, Float value) {
            buffer.ensureWritable(4);
            impl(buffer)._putFloat(buffer.writeIndex(), value);
            buffer.advanceWrite(4);
        }

        @Override
        public Float read(NetworkBuffer buffer) {
            final float value = impl(buffer)._getFloat(buffer.readIndex());
            buffer.advanceRead(4);
            return value;
        }
    }

    record DoubleType() implements NetworkBufferTypeImpl<Double> {
        @Override
        public void write(NetworkBuffer buffer, Double value) {
            buffer.ensureWritable(8);
            impl(buffer)._putDouble(buffer.writeIndex(), value);
            buffer.advanceWrite(8);
        }

        @Override
        public Double read(NetworkBuffer buffer) {
            final double value = impl(buffer)._getDouble(buffer.readIndex());
            buffer.advanceRead(8);
            return value;
        }
    }

    record UUIDType() implements NetworkBufferTypeImpl<UUID> {
        @Override
        public void write(NetworkBuffer buffer, UUID value) {
            buffer.write(LONG, value.getMostSignificantBits());
            buffer.write(LONG, value.getLeastSignificantBits());
        }

        @Override
        public UUID read(NetworkBuffer buffer) {
            final long mostSignificantBits = buffer.read(LONG);
            final long leastSignificantBits = buffer.read(LONG);
            return new UUID(mostSignificantBits, leastSignificantBits);
        }
    }
}
