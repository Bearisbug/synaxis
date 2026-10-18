package io.github.bearisbug.synaxis.thread;

/**
 * Represents an object that has been safely acquired and can be freed again.
 * <p>
 * This class should not be shared. Unlock it once the acquisition goal has been fulfilled to limit
 * blocking time, best with try-with-resources:
 * <pre>{@code
 * try (var acquired = entity.acquirable().lock()) {
 *     acquired.get().teleport(spawn);
 * }
 * }</pre>
 *
 * @param <T> the type of the acquired object
 */
public sealed interface Acquired<T> extends AutoCloseable permits AcquiredImpl {
    T get();

    void unlock();

    /**
     * Unlocks the object unless {@link #unlock()} already did.
     *
     * @throws IllegalStateException if called from another thread than the one that acquired it
     */
    @Override
    void close();
}
