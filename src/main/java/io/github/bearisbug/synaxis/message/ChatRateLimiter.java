package io.github.bearisbug.synaxis.message;

import org.jetbrains.annotations.ApiStatus;

/**
 * The chat token bucket of one connection (docs/DESIGN-PROD.md API-PROD-008): {@code ratePerSecond}
 * messages a second on average, at most {@code burst} back to back. A message over the limit is
 * dropped and counted, the connection stays.
 * <p>
 * Only the thread interpreting the player's packets uses it, so nothing here is synchronized.
 */
@ApiStatus.Internal
public final class ChatRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long interval;
    private final long tolerance;
    /** When the bucket is full again; each accepted message pushes it one interval further. */
    private long fullAt = Long.MIN_VALUE;
    private long dropped;

    public ChatRateLimiter(int ratePerSecond, int burst) {
        this.interval = NANOS_PER_SECOND / ratePerSecond;
        this.tolerance = (burst - 1) * interval;
    }

    /**
     * Takes one token if there is one.
     *
     * @param nanoTime the current {@link System#nanoTime()}
     * @return true if the message may go on, false if it is dropped
     */
    public boolean tryAcquire(long nanoTime) {
        final long start = Math.max(fullAt, nanoTime);
        if (start - nanoTime > tolerance) {
            dropped++;
            return false;
        }
        fullAt = start + interval;
        return true;
    }

    /** Returns how many messages this connection had dropped. */
    public long dropped() {
        return dropped;
    }
}
