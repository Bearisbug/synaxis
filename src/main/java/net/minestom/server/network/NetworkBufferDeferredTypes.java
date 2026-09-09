package net.minestom.server.network;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

final class NetworkBufferDeferredTypes {

    static final class LazyType<T> implements NetworkBufferTypeImpl<T> {
        private final Supplier<NetworkBuffer.Type<T>> supplier;
        private NetworkBuffer.Type<T> type;

        public LazyType(Supplier<NetworkBuffer.Type<T>> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void write(NetworkBuffer buffer, T value) {
            if (type == null) type = supplier.get();
            type.write(buffer, value);
        }

        @Override
        public T read(NetworkBuffer buffer) {
            if (type == null) type = supplier.get();
            return type.read(buffer);
        }
    }

    static final class RecursiveType<T> implements NetworkBufferTypeImpl<T> {
        // The nesting depth of a recursive type is decided by whoever wrote the bytes, so it has
        // to be bounded before the recursive read runs the java stack out (see ServerFlag#NBT_MAX_DEPTH).
        private static final int MAX_READ_DEPTH = 64;

        final NetworkBuffer.Type<T> delegate;
        private final ThreadLocal<int[]> readDepth = ThreadLocal.withInitial(() -> new int[1]);

        public RecursiveType(Function<NetworkBuffer.Type<T>, NetworkBuffer.Type<T>> self) {
            Objects.requireNonNull(self, "self");
            this.delegate = Objects.requireNonNull(self.apply(this), "delegate");
        }

        @Override
        public void write(NetworkBuffer buffer, T value) {
            delegate.write(buffer, value);
        }

        @Override
        public T read(NetworkBuffer buffer) {
            final int[] depth = readDepth.get();
            if (depth[0] >= MAX_READ_DEPTH)
                throw new IllegalStateException("Recursive type nested deeper than " + MAX_READ_DEPTH + " levels");
            depth[0]++;
            try {
                return delegate.read(buffer);
            } finally {
                depth[0]--;
            }
        }
    }
}
