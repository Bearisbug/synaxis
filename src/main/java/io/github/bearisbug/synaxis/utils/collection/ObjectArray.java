package io.github.bearisbug.synaxis.utils.collection;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.List;

/**
 * Represents an array which will be resized to the highest required index.
 *
 * @param <T> the type of the array
 */
@ApiStatus.Internal
public final class ObjectArray<T> {
    private T[] array;
    private int max = -1;

    public static <T> ObjectArray<T> singleThread(int initialSize) {
        return new ObjectArray<>(initialSize);
    }

    public static <T> ObjectArray<T> singleThread() {
        return singleThread(0);
    }

    @SuppressWarnings("unchecked")
    private ObjectArray(int size) {
        this.array = (T[]) new Object[size];
    }

    public @UnknownNullability T get(int index) {
        final T[] array = this.array;
        return index < array.length ? array[index] : null;
    }

    public void set(int index, @Nullable T object) {
        if (object == null) {
            remove(index);
            return;
        }
        T[] array = this.array;
        if (index >= array.length) {
            final int newLength = index * 2 + 1;
            this.array = array = Arrays.copyOf(array, newLength);
        }
        array[index] = object;
        this.max = Math.max(max, index);
    }

    public void remove(int index) {
        final T[] array = this.array;
        if (index >= array.length) return; // Will be null anyway
        array[index] = null;
        // Now we need to backtrack the max index,
        // For example [0, 1, 2, null, 4] removing 4 requires us to backtrack past the null
        final int max = this.max;
        if (max == index) {
            int lastNotNull = max - 1;
            while (lastNotNull >= 0 && array[lastNotNull] == null) {
                lastNotNull--;
            }
            this.max = lastNotNull;
        }
    }

    /**
     * Copies the array into a list.
     * Requires all elements to be present and indexed from 0.
     *
     * @return List of the array elements
     */
    @Contract(pure = true)
    public @Unmodifiable List<T> toList() {
        // Trim the array to the maximum size, it internally will be copied regardless.
        final T[] array = Arrays.copyOf(this.array, max + 1);
        return List.of(array);
    }
}
