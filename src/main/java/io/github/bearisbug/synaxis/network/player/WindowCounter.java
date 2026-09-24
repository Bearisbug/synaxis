package io.github.bearisbug.synaxis.network.player;

import java.util.Arrays;

/**
 * A sum over a sliding window, kept in twenty buckets: what it reports covers the last 19/20 to
 * 20/20 of the window. One connection, one thread, no locking.
 */
final class WindowCounter {

    private static final int BUCKETS = 20;

    private final long bucketNanos;
    private final long[] buckets = new long[BUCKETS];
    private long current;
    private boolean started;
    private long sum;

    WindowCounter(long windowNanos) {
        this.bucketNanos = Math.max(1, windowNanos / BUCKETS);
    }

    /**
     * Adds to the window.
     *
     * @param now    {@link System#nanoTime()}
     * @param amount what to add
     * @return the sum over the window, this amount included
     */
    long add(long now, long amount) {
        final long bucket = Math.floorDiv(now, bucketNanos);
        if (!started) {
            this.current = bucket;
            this.started = true;
        } else if (bucket > current) {
            if (bucket - current >= BUCKETS) {
                Arrays.fill(buckets, 0);
                this.sum = 0;
            } else {
                for (long expired = current + 1; expired <= bucket; expired++) {
                    final int index = Math.floorMod(expired, BUCKETS);
                    this.sum -= buckets[index];
                    buckets[index] = 0;
                }
            }
            this.current = bucket;
        }
        buckets[Math.floorMod(current, BUCKETS)] += amount;
        this.sum += amount;
        return sum;
    }
}
