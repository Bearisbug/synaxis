package io.github.bearisbug.synaxis.utils;

import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.ApiStatus;

import java.lang.ref.Cleaner;
import java.lang.ref.SoftReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

@ApiStatus.Internal
@ApiStatus.Experimental
public final class ObjectPool<T> {
    private static final int QUEUE_SIZE = 32_768;
    private static final Cleaner CLEANER = Cleaner.create();

    private final MessagePassingQueue<SoftReference<T>> pool;
    private final Supplier<T> supplier;
    private final UnaryOperator<T> sanitizer;

    public static <T> ObjectPool<T> pool(Supplier<T> supplier, UnaryOperator<T> sanitizer) {
        return new ObjectPool<>(supplier, sanitizer);
    }

    public static <T> ObjectPool<T> pool(Supplier<T> supplier) {
        return new ObjectPool<>(supplier, UnaryOperator.identity());
    }

    private ObjectPool(Supplier<T> supplier, UnaryOperator<T> sanitizer) {
        this.pool = ConcurrentMessageQueues.mpmcArrayQueue(QUEUE_SIZE);
        this.supplier = supplier;
        this.sanitizer = sanitizer;
    }

    public T get() {
        T result;
        SoftReference<T> ref;
        while ((ref = pool.poll()) != null) {
            if ((result = ref.get()) != null) return result;
        }
        return supplier.get();
    }

    public T getAndRegister(Object ref) {
        T result = get();
        register(ref, result);
        return result;
    }

    public void add(T object) {
        object = sanitizer.apply(object);
        this.pool.offer(new SoftReference<>(object));
    }

    public void clear() {
        this.pool.clear();
    }

    public int count() {
        return pool.size();
    }

    public void register(Object ref, T object) {
        CLEANER.register(ref, new BufferCleaner<>(this, object));
    }

    private record BufferCleaner<T>(ObjectPool<T> pool, T object) implements Runnable {
        @Override
        public void run() {
            this.pool.add(object);
        }
    }
}
