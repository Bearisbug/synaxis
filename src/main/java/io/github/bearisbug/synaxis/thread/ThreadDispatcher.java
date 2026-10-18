package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * ThreadDispatcher can be used to dispatch updates (ticks) across a number of "partitions" (such as chunks) that
 * house {@link Tickable} instances (such as entities). The parallelism of such updates is defined when the dispatcher
 * is constructed.
 * <p>
 * It is recommended that {@link Tickable}s being added to a dispatcher also implement {@link AcquirableSource}, as
 * doing so will allow the user to synchronize external access to them using the {@link Acquirable} API.
 * <p>
 * Instances of this class can be obtained by calling {@link ThreadDispatcher#dispatcher(ThreadProvider, int, Consumer)}, or a similar
 * overload.
 *
 * @see Acquirable
 * @see AcquirableSource
 */
public sealed interface ThreadDispatcher<P, E extends Tickable> permits ThreadDispatcherImpl {
    /**
     * Creates a new ThreadDispatcher using default thread names (ex. Ge-Tick-n).
     * <p>Remember to start the dispatcher using {@link #start()}</p>
     *
     * @param provider         the {@link ThreadProvider} instance to be used for defining thread IDs
     * @param threadCount      the number of threads to create for this dispatcher
     * @param exceptionHandler where the exceptions thrown while ticking are reported
     * @param <P>              the dispatcher partition type
     * @return a new ThreadDispatcher instance
     */
    @Contract(pure = true)
    static <P, E extends Tickable> ThreadDispatcher<P, E> dispatcher(ThreadProvider<P> provider, int threadCount,
                                                                     Consumer<Throwable> exceptionHandler) {
        return new ThreadDispatcherImpl<>(provider, threadCount, index -> new TickThread(index, exceptionHandler));
    }

    /**
     * Creates a new ThreadDispatcher using the caller-provided thread name generator {@code nameGenerator}. This is
     * useful to disambiguate custom ThreadDispatcher instances from ones used in core engine code.
     * <p>Remember to start the dispatcher using {@link #start()}</p>
     *
     * @param provider         the {@link ThreadProvider} instance to be used for defining thread IDs
     * @param nameGenerator    a function that should return unique names, given a thread index
     * @param threadCount      the number of threads to create for this dispatcher
     * @param exceptionHandler where the exceptions thrown while ticking are reported
     * @param <P>              the dispatcher partition type
     * @return a new ThreadDispatcher instance
     */
    @Contract(pure = true)
    static <P, E extends Tickable> ThreadDispatcher<P, E> dispatcher(ThreadProvider<P> provider,
                                                                     IntFunction<String> nameGenerator, int threadCount,
                                                                     Consumer<Throwable> exceptionHandler) {
        return new ThreadDispatcherImpl<>(provider, threadCount,
                index -> new TickThread(nameGenerator.apply(index), exceptionHandler));
    }

    /**
     * Creates a single-threaded dispatcher that uses default thread names.
     * <p>Remember to start the dispatcher using {@link #start()}</p>
     *
     * @param exceptionHandler where the exceptions thrown while ticking are reported
     * @param <P>              the dispatcher partition type
     * @return a new ThreadDispatcher instance
     */
    @Contract(pure = true)
    static <P, E extends Tickable> ThreadDispatcher<P, E> singleThread(Consumer<Throwable> exceptionHandler) {
        return dispatcher(ThreadProvider.counter(), 1, exceptionHandler);
    }

    /**
     * Gets the unmodifiable list of TickThreads used to dispatch updates.
     * <p>
     * This method is marked internal to reflect {@link TickThread}s own internal status.
     *
     * @return the TickThreads used to dispatch updates
     */
    @Unmodifiable
    @ApiStatus.Internal
    List<TickThread> threads();

    /**
     * Prepares the update by creating the {@link TickThread} tasks.
     *
     * @param time the tick time in nanos
     */
    void updateAndAwait(long time);

    /**
     * Called at the end of each tick to clear removed tickables, refresh the partition linked to a tickable, and
     * partition threads based on {@link ThreadProvider#findThread(Object)}.
     *
     * @param nanoTimeout max time in nanoseconds to update partitions
     */
    void refreshThreads(long nanoTimeout);

    /**
     * Refreshes all thread as per {@link ThreadDispatcher#refreshThreads(long)}, with a timeout of
     * {@link Long#MAX_VALUE}.
     */
    default void refreshThreads() {
        refreshThreads(Long.MAX_VALUE);
    }

    /**
     * Signals an update to the dispatcher.
     * <p>
     * This method is used to notify the dispatcher of changes that need to be processed, such as partition loads,
     * unloads, or element updates.
     * <p>
     * Updates are processed at the start of each tick, before the actual ticking of elements.
     *
     * @param update the update to signal
     */
    void signalUpdate(ThreadDispatcher.Update<P, E> update);

    /**
     * Signals a new partition, which the dispatcher has from the next processing of the updates on. A
     * partition that is a {@link Tickable} ticks as an element of itself; signalling a partition the
     * dispatcher already has does nothing.
     * <p>
     * This signal has to be queued before any {@link #updateElement} pointing at the partition: an
     * update processed while the partition does not exist takes the element out of every partition,
     * and the element stops ticking.
     *
     * @param partition the partition
     */
    default void createPartition(P partition) {
        signalUpdate(new Update.PartitionLoad<>(partition));
    }

    /**
     * Signals that a partition is gone. The elements in it stop ticking with it, until an
     * {@link #updateElement} puts them in another partition.
     *
     * @param partition the partition
     */
    default void deletePartition(P partition) {
        signalUpdate(new Update.PartitionUnload<>(partition));
    }

    /**
     * Signals that {@code element} belongs to {@code partition}, which it moves to at the next processing
     * of the updates. The {@link #createPartition} of that partition has to be queued before this
     * update: processed while the partition does not exist, the update takes the element out of every
     * partition, and the element stops ticking.
     *
     * @param element   the element
     * @param partition the partition it belongs to now
     */
    default void updateElement(E element, P partition) {
        signalUpdate(new Update.ElementUpdate<>(element, partition));
    }

    default void removeElement(E element) {
        signalUpdate(new Update.ElementRemove<>(element));
    }

    /**
     * Starts all the {@link TickThread tick threads}.
     * <p>
     * This will throw an {@link IllegalThreadStateException} if the threads have already been started.
     */
    void start();

    /**
     * Checks if all the {@link TickThread tick threads} are alive.
     *
     * @return true if all threads are alive, false otherwise
     */
    boolean isAlive();

    /**
     * Shutdowns all the {@link TickThread tick threads}.
     * <p>
     * Action is irreversible.
     */
    void shutdown();

    @ApiStatus.Internal
    @SuppressWarnings("unused")
    sealed interface Update<P, E> {

        /**
         * Registers a new partition.
         *
         * @param partition the partition to register
         */
        record PartitionLoad<P, E>(P partition) implements Update<P, E> {
        }

        /**
         * Deletes an existing partition.
         *
         * @param partition the partition to delete
         */
        record PartitionUnload<P, E>(P partition) implements Update<P, E> {
        }

        /**
         * Updates an element}, signalling that it is a part of {@code partition}.
         *
         * @param element   the element to update
         * @param partition the partition the Tickable is part of
         */
        record ElementUpdate<P, E>(E element, P partition) implements Update<P, E> {
        }

        /**
         * Removes an element.
         *
         * @param element the element to remove
         */
        record ElementRemove<P, E>(E element) implements Update<P, E> {
        }
    }
}
