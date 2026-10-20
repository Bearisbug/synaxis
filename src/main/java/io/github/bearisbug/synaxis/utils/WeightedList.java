package io.github.bearisbug.synaxis.utils;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public final class WeightedList<T> implements Iterable<T> {
    public static <T> Codec<WeightedList<T>> codec(StructCodec<T> valueCodec) {
        return Entry.codec(valueCodec).list().transform(WeightedList::new, WeightedList::entries);
    }
    public static <T> Codec<WeightedList<T>> codec(Codec<T> valueCodec) {
        StructCodec<T> wrapper = StructCodec.struct("data", valueCodec, t -> t, t -> t);
        return Entry.codec(wrapper).list().transform(WeightedList::new, WeightedList::entries);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> WeightedList<T> of(Entry<T>... entries) {
        return new WeightedList<>(List.of(entries));
    }

    private final List<Entry<T>> entries;
    private final int totalWeight;

    /**
     * Creates a weighted list.
     *
     * @param entries the entries
     * @throws IllegalArgumentException if a weight is negative or the weights add up to more than an int holds
     */
    public WeightedList(List<Entry<T>> entries) {
        this.entries = List.copyOf(entries);

        int total = 0;
        for (int i = 0; i < this.entries.size(); i++) {
            final int weight = this.entries.get(i).weight();
            if (weight < 0) throw new IllegalArgumentException("weight of entry " + i + " is negative: " + weight);
            if (weight > Integer.MAX_VALUE - total) throw new IllegalArgumentException("total weight overflows int");
            total += weight;
        }
        this.totalWeight = total;
    }

    public List<Entry<T>> entries() {
        return entries;
    }

    /**
     * Picks an entry at random, each with a chance proportional to its weight.
     *
     * @param random the random source
     * @return the value of the picked entry, or null if no entry has a positive weight
     */
    public @Nullable T pick(Random random) {
        if (totalWeight == 0) return null;
        int pick = random.nextInt(totalWeight);
        for (Entry<T> entry : entries) {
            pick -= entry.weight();
            if (pick < 0) return entry.value();
        }
        return null;
    }

    public T pickOrThrow(Random random) {
        return Objects.requireNonNull(pick(random), "WeightedList has no entry with positive weight");
    }

    @Override
    public Iterator<T> iterator() {
        final var delegate = entries.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public T next() {
                return delegate.next().value();
            }
        };
    }

    public record Entry<T>(T value, int weight) {
        public static <T> StructCodec<Entry<T>> codec(StructCodec<T> valueCodec) {
            return StructCodec.struct(
                    StructCodec.INLINE, valueCodec, Entry::value,
                    "weight", Codec.INT, Entry::weight,
                    Entry::new);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WeightedList<?> that)) return false;
        return totalWeight == that.totalWeight && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        int result = entries.hashCode();
        result = 31 * result + totalWeight;
        return result;
    }
}
