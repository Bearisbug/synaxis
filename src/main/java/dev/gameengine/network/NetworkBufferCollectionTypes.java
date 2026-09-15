package dev.gameengine.network;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import dev.gameengine.utils.validate.Check;

import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.FixedBitSet;
import static dev.gameengine.network.NetworkBuffer.FixedRawBytes;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

final class NetworkBufferCollectionTypes {

    record EnumSetType<E extends Enum<E>>(Class<E> enumType,
                                          E[] values, NetworkBuffer.Type<BitSet> bitSetType) implements NetworkBuffer.Type<EnumSet<E>> {
        public EnumSetType {
            Objects.requireNonNull(enumType, "enumType");
            Objects.requireNonNull(values, "values");
            Objects.requireNonNull(bitSetType, "bitSetType");
        }

        public EnumSetType(Class<E> enumClass, E[] values) {
            this(enumClass, values, FixedBitSet(values.length));
        }

        @Override
        public void write(NetworkBuffer buffer, EnumSet<E> value) {
            BitSet bitSet = new BitSet(values.length);
            for (int i = 0; i < values.length; ++i) {
                bitSet.set(i, value.contains(values[i]));
            }
            bitSetType.write(buffer, bitSet);
        }

        @Override
        public EnumSet<E> read(NetworkBuffer buffer) {
            final BitSet bitSet = bitSetType.read(buffer);
            EnumSet<E> enumSet = EnumSet.noneOf(enumType);
            for (int i = 0; i < values.length; ++i) {
                if (bitSet.get(i)) {
                    enumSet.add(values[i]);
                }
            }
            return enumSet;
        }
    }

    record FixedBitSetType(int length, NetworkBuffer.Type<byte[]> arrayType) implements NetworkBuffer.Type<BitSet> {
        public FixedBitSetType {
            Check.argCondition(length < 0, "Length is negative found {0}", length);
            Objects.requireNonNull(arrayType, "arrayType");
        }

        public FixedBitSetType(int length) {
            this(length, FixedRawBytes((length + 7) / Long.BYTES));
        }

        @Override
        public void write(NetworkBuffer buffer, BitSet value) {
            if (value.length() > length) {
                throw new IllegalArgumentException("BitSet is larger than expected size (" + value.length() + ">" + length + ")");
            }
            byte[] array = value.toByteArray();
            final int length = (this.length + 7) / Long.BYTES;
            if (array.length != length) {
                array = Arrays.copyOf(array, length);
            }
            arrayType.write(buffer, array);
        }

        @Override
        public BitSet read(NetworkBuffer buffer) {
            final byte[] array = arrayType.read(buffer);
            return BitSet.valueOf(array);
        }
    }

    record MapType<K, V>(NetworkBuffer.Type<K> parent, NetworkBuffer.Type<V> valueType,
                         int maxSize) implements NetworkBufferTypeImpl<Map<K, V>> {
        @Override
        public void write(NetworkBuffer buffer, Map<K, V> map) {
            buffer.write(VAR_INT, map.size());
            for (Map.Entry<K, V> entry : map.entrySet()) {
                buffer.write(parent, entry.getKey());
                buffer.write(valueType, entry.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<K, V> read(NetworkBuffer buffer) {
            final int size = buffer.read(VAR_INT);
            Check.argCondition(size < 0 || size > maxSize, "Map size ({0}) is out of the allowed range (0..{1})", size, maxSize);
            final int initialSize = NetworkBufferTypeImpl.initialCollectionSize(buffer, size);
            K[] keys = (K[]) new Object[initialSize];
            V[] values = (V[]) new Object[initialSize];
            for (int i = 0; i < size; i++) {
                if (i == keys.length) {
                    final int newSize = NetworkBufferTypeImpl.grownCollectionSize(keys.length, size);
                    keys = Arrays.copyOf(keys, newSize);
                    values = Arrays.copyOf(values, newSize);
                }
                keys[i] = buffer.read(parent);
                values[i] = buffer.read(valueType);
            }
            return Map.copyOf(new Object2ObjectArrayMap<>(keys, values, size));
        }
    }

    record ListType<T>(NetworkBuffer.Type<T> parent, int maxSize) implements NetworkBufferTypeImpl<List<T>> {
        @Override
        public void write(NetworkBuffer buffer, List<T> values) {
            if (values == null) {
                buffer.write(BYTE, (byte) 0);
                return;
            }
            buffer.write(VAR_INT, values.size());
            for (T value : values) buffer.write(parent, value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public List<T> read(NetworkBuffer buffer) {
            final int size = buffer.read(VAR_INT);
            Check.argCondition(size < 0 || size > maxSize, "Collection size ({0}) is out of the allowed range (0..{1})", size, maxSize);
            T[] values = (T[]) new Object[NetworkBufferTypeImpl.initialCollectionSize(buffer, size)];
            for (int i = 0; i < size; i++) {
                if (i == values.length) {
                    values = Arrays.copyOf(values, NetworkBufferTypeImpl.grownCollectionSize(values.length, size));
                }
                values[i] = buffer.read(parent);
            }
            return List.of(values);
        }
    }

    record SetType<T>(NetworkBuffer.Type<T> parent, int maxSize) implements NetworkBufferTypeImpl<Set<T>> {
        @Override
        public void write(NetworkBuffer buffer, Set<T> values) {
            if (values == null) {
                buffer.write(BYTE, (byte) 0);
                return;
            }
            buffer.write(VAR_INT, values.size());
            for (T value : values) buffer.write(parent, value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<T> read(NetworkBuffer buffer) {
            final int size = buffer.read(VAR_INT);
            Check.argCondition(size < 0 || size > maxSize, "Collection size ({0}) is out of the allowed range (0..{1})", size, maxSize);
            T[] values = (T[]) new Object[NetworkBufferTypeImpl.initialCollectionSize(buffer, size)];
            for (int i = 0; i < size; i++) {
                if (i == values.length) {
                    values = Arrays.copyOf(values, NetworkBufferTypeImpl.grownCollectionSize(values.length, size));
                }
                values[i] = buffer.read(parent);
            }
            return Set.of(values);
        }
    }
}
