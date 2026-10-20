package io.github.bearisbug.synaxis.thread;

/**
 * Represents an element which is ticked at a regular interval.
 */
public interface Tickable {

    /**
     * Ticks this element.
     *
     * @param time the time the tick started at, from a monotonic clock: it only means something as a difference with
     *             the other times passed to the same element. Elements ticked by a tick thread get
     *             {@link System#nanoTime()} converted to milliseconds
     */
    void tick(long time);
}
