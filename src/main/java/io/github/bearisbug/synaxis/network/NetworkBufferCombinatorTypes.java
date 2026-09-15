package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.utils.Either;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

final class NetworkBufferCombinatorTypes {

    record OptionalType<T>(NetworkBuffer.Type<T> parent) implements NetworkBufferTypeImpl<@Nullable T> {
        @Override
        public void write(NetworkBuffer buffer, T value) {
            buffer.write(BOOLEAN, value != null);
            if (value != null) buffer.write(parent, value);
        }

        @Override
        public T read(NetworkBuffer buffer) {
            return buffer.read(BOOLEAN) ? buffer.read(parent) : null;
        }
    }

    record LengthPrefixedType<T>(NetworkBuffer.Type<T> parent, int maxLength) implements NetworkBufferTypeImpl<T> {
        @Override
        public void write(NetworkBuffer buffer, T value) {
            // Write to another buffer and copy (kinda inefficient, but currently unused serverside so its ok for now)
            final byte[] componentData = NetworkBuffer.makeArray(b -> parent.write(b, value), buffer.registries());
            buffer.write(NetworkBuffer.BYTE_ARRAY, componentData);
        }

        @Override
        public T read(NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            Check.argCondition(length > maxLength, "Value is too long (length: {0}, max: {1})", length, maxLength);

            final long availableBytes = buffer.readableBytes();
            Check.argCondition(length > availableBytes, "Value is too long (length: {0}, available: {1})", length, availableBytes);
            final T value = parent.read(buffer);
            Check.argCondition(buffer.readableBytes() != availableBytes - length, "Value is too short (length: {0}, available: {1})", length, availableBytes);

            return value;
        }
    }

    record MaxLength<T>(NetworkBuffer.Type<T> parent, long maxLength) implements NetworkBufferTypeImpl<T> {
        @Override
        public void write(NetworkBuffer buffer, T value) {
            final long length = parent.sizeOf(value, buffer.registries());
            Check.argCondition(length > maxLength, "Value is too long (length: {0}, max: {1})", length, maxLength);
            buffer.write(parent, value);
        }

        @Override
        public T read(NetworkBuffer buffer) {
            final long index = buffer.readIndex();
            final T value = parent.read(buffer);
            final long length = buffer.readIndex() - index;
            Check.argCondition(length > maxLength, "Value is too long (length: {0}, max: {1})", length, maxLength);
            return value;
        }
    }

    record EitherType<L, R>(
            NetworkBuffer.Type<L> left,
            NetworkBuffer.Type<R> right
    ) implements NetworkBuffer.Type<Either<L, R>> {
        @Override
        public void write(NetworkBuffer buffer, Either<L, R> value) {
            switch (value) {
                case Either.Left(L leftValue) -> {
                    buffer.write(BOOLEAN, true);
                    buffer.write(left, leftValue);
                }
                case Either.Right(R rightValue) -> {
                    buffer.write(BOOLEAN, false);
                    buffer.write(right, rightValue);
                }
            }
        }

        @Override
        public Either<L, R> read(NetworkBuffer buffer) {
            if (buffer.read(BOOLEAN))
                return Either.left(buffer.read(left));
            return Either.right(buffer.read(right));
        }
    }

    record TransformType<T, S>(NetworkBuffer.Type<T> parent, Function<T, S> to,
                               Function<S, T> from) implements NetworkBufferTypeImpl<S> {
        @Override
        public void write(NetworkBuffer buffer, S value) {
            parent.write(buffer, from.apply(value));
        }

        @Override
        public S read(NetworkBuffer buffer) {
            return to.apply(parent.read(buffer));
        }
    }

    record UnionType<T, K, TR extends T>(
            NetworkBuffer.Type<K> keyType, Function<T, ? extends K> keyFunc,
            Function<K, NetworkBuffer.Type<TR>> serializers
    ) implements NetworkBufferTypeImpl<T> {

        @SuppressWarnings("unchecked")
        // Much nicer than using the correct wildcard type for returns, pretty much ensuring T has subtypes already.
        @Override
        public void write(NetworkBuffer buffer, T value) {
            final K key = keyFunc.apply(value);
            buffer.write(keyType, key);
            var serializer = serializers.apply(key);
            if (serializer == null)
                throw new UnsupportedOperationException("Unrecognized type: " + key);
            serializer.write(buffer, (TR) value);
        }

        @Override
        public T read(NetworkBuffer buffer) {
            final K key = buffer.read(keyType);
            var serializer = serializers.apply(key);
            if (serializer == null) throw new UnsupportedOperationException("Unrecognized type: " + key);
            return serializer.read(buffer);
        }
    }

    record TaggedType<T, D>(
            NetworkBuffer.Type<D> discriminatorType, Function<? super T, ? extends D> discriminatorFromValue,
            Map<? super D, NetworkBuffer.Type<? extends T>> serializerMap, @Nullable NetworkBuffer.Type<? extends T> fallback
    ) implements NetworkBufferTypeImpl<T> {
        public TaggedType {
            Objects.requireNonNull(discriminatorType, "discriminatorType");
            Objects.requireNonNull(discriminatorFromValue, "discriminatorFromValue");
            serializerMap = Map.copyOf(serializerMap);
        }

        @SuppressWarnings("unchecked") // Likely fine here
        @Override
        public void write(NetworkBuffer buffer, T value) {
            final D key = discriminatorFromValue.apply(value);
            buffer.write(discriminatorType, key);
            var serializer = serializerMap.getOrDefault(key, fallback);
            if (serializer == null)
                throw new UnsupportedOperationException("Unrecognized type: " + key);
            ((NetworkBuffer.Type<T>) serializer).write(buffer, value);
        }

        @Override
        public T read(NetworkBuffer buffer) {
            final D key = buffer.read(discriminatorType);
            var serializer = serializerMap.getOrDefault(key, fallback);
            if (serializer == null) throw new UnsupportedOperationException("Unrecognized type: " + key);
            return serializer.read(buffer);
        }
    }
}
