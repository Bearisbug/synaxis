package io.github.bearisbug.synaxis.tag;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.UnknownNullability;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The entries of a tag node, an array indexed by tag index. The keys are the indexes {@code TagImpl.INDEX_MAP} hands
 * out, one per tag key in the whole JVM, and the array grows to {@code 2 * key + 1} slots for the largest key put: its
 * size follows how many tag keys the JVM has seen, not how many entries the node holds. A node holding only the tag
 * of index 5000 has 10001 slots.
 */
final class StaticIntMap<T extends @UnknownNullability Object> {
    private static final Object[] EMPTY_ARRAY = new Object[0];

    @SuppressWarnings("unchecked")
    private T[] array = (T[]) EMPTY_ARRAY;

    @Nullable T get(@Range(from = 0, to = Integer.MAX_VALUE) int key) {
        final T[] array = this.array;
        return key < array.length ? array[key] : null;
    }

    void forValues(Consumer<T> consumer) {
        final T[] array = this.array;
        for (T value : array) {
            if (value != null) consumer.accept(value);
        }
    }

    void put(@Range(from = 0, to = Integer.MAX_VALUE) int key, T value) {
        T[] array = this.array;
        if (key >= array.length) {
            array = Arrays.copyOf(array, key * 2 + 1);
            this.array = array;
        }
        array[key] = value;
    }

    void remove(@Range(from = 0, to = Integer.MAX_VALUE) int key) {
        T[] array = this.array;
        if (key < array.length) array[key] = null;
    }

    /** Drops every entry. The shared empty array is never written to: a put always grows it first. */
    @SuppressWarnings("unchecked")
    void clear() {
        this.array = (T[]) EMPTY_ARRAY;
    }

    @Override
    public String toString() {
        return Arrays.stream(array).filter(Objects::nonNull).toList().toString();
    }
}
