package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

@FunctionalInterface
@ApiStatus.Experimental
public interface ThreadProvider<T> {
    static <T> ThreadProvider<T> counter() {
        return new ThreadProvider<>() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public int findThread(T partition) {
                return counter.getAndIncrement();
            }
        };
    }

    /**
     * Groups the chunks into square cells of {@code 2^exponent} by {@code 2^exponent} chunks and gives
     * every chunk of a cell the same thread ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}), so that
     * neighbours interact on one thread and only the interactions across a cell border go through
     * {@link Acquirable}. The thread id of cell (x, z) is {@code 3x + z}, which the dispatcher maps
     * onto its threads with {@link Math#floorMod(int, int)}; the chunks of every instance share the
     * grid. From six threads on, two cells that touch, corners included, never share a thread, and
     * from ten on neither do two cells two apart. Players crowd into neighbouring cells, which is why
     * the id must not be the {@code 31x + z} shape of {@link java.util.Objects#hash}: that one puts
     * every cell and its neighbour across the corner on one thread whenever the thread count divides
     * 30, six and ten included.
     *
     * @param exponent the cell side as a power of two in chunks, 4 for cells of 16 by 16 chunks
     * @param chunkX   the chunk x coordinate of a partition
     * @param chunkZ   the chunk z coordinate of a partition
     * @param <T>      the partition type
     * @return a provider that never moves a partition to another thread
     * @throws IllegalArgumentException if the exponent is not between 0 and 31
     */
    static <T> ThreadProvider<T> grid(int exponent, ToIntFunction<? super T> chunkX, ToIntFunction<? super T> chunkZ) {
        if (exponent < 0 || exponent > 31)
            throw new IllegalArgumentException("the grid exponent must be between 0 and 31 but was " + exponent);
        return partition -> 3 * (chunkX.applyAsInt(partition) >> exponent) + (chunkZ.applyAsInt(partition) >> exponent);
    }

    /**
     * Performs a server tick for all chunks based on their linked thread.
     *
     * @param partition the partition
     */
    int findThread(T partition);

    /**
     * Defines how often chunks thread should be updated.
     *
     * @return the refresh type
     */
    default RefreshType refreshType() {
        return RefreshType.NEVER;
    }

    /**
     * Defines how often chunks thread should be refreshed.
     */
    enum RefreshType {
        /**
         * Thread never change after being defined once.
         * <p>
         * Means that {@link #findThread(Object)} will only be called once for each partition.
         */
        NEVER,
        /**
         * Thread is updated as often as possible.
         * <p>
         * Means that {@link #findThread(Object)} may be called multiple time for each partition.
         */
        ALWAYS
    }
}
