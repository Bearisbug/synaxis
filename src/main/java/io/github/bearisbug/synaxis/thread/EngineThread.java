package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
@ApiStatus.NonExtendable
public class EngineThread extends Thread {
    // Only this thread adds to it, a plain field: nothing shared is written per acquisition
    private long acquireWaitNanos;

    public EngineThread(String name) {
        super(name);
    }

    /**
     * Wall time this thread has spent blocked on a lock of the acquisition protocol since it started,
     * in nanoseconds ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}): the global lock and the lock of
     * the tick thread owning an element, while acquiring it; for a tick thread also its own lock, taken
     * back from an acquirer at the start of a tick or after handing it over between two elements.
     * <p>
     * Only this thread writes it: read it on this thread, or on a thread that waited for this one to
     * be done, as {@link ThreadDispatcher#updateAndAwait(long)} waits for the tick of every tick thread.
     *
     * @return the total, in nanoseconds
     */
    public long acquireWaitNanos() {
        return acquireWaitNanos;
    }

    void waited(long nanos) {
        acquireWaitNanos += nanos;
    }
}
