package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.event.server.ServerTickMonitorEvent;

import java.util.Arrays;

/**
 * The last {@value #WINDOW} ticks, 60 s at 20 TPS, for the {@code tps} command
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}): when each ended and how long it took.
 * Written by the tick scheduler thread, read by whichever thread runs the command, and by the
 * management method {@code synaxis:shard/status} ({@code API-PROD-022}).
 */
public final class TickStats {

    static final int WINDOW = 1200;

    /**
     * What the window holds.
     *
     * @param ticks the number of ticks in the window
     * @param tps   ticks per second between the first and the last of them, 0 below two ticks
     * @param p50   the median tick duration in ms
     * @param p95   the 95th percentile in ms
     * @param p99   the 99th percentile in ms
     */
    public record Snapshot(int ticks, double tps, double p50, double p95, double p99) {
    }

    private final long[] endedAt = new long[WINDOW];
    private final double[] durations = new double[WINDOW];
    // Guarded by this: the next slot, and how many slots hold a tick
    private int next;
    private int size;

    /**
     * Records one tick.
     *
     * @param event the monitor event the engine fires at the end of every tick
     */
    void record(ServerTickMonitorEvent event) {
        record(System.nanoTime(), event.getTickMonitor().getTickTime());
    }

    synchronized void record(long endedAtNanos, double durationMillis) {
        endedAt[next] = endedAtNanos;
        durations[next] = durationMillis;
        next = (next + 1) % WINDOW;
        size = Math.min(size + 1, WINDOW);
    }

    synchronized Snapshot snapshot() {
        if (size == 0) return new Snapshot(0, 0, 0, 0, 0);
        final int oldest = (next - size + WINDOW) % WINDOW;
        final int newest = (next - 1 + WINDOW) % WINDOW;
        final long span = endedAt[newest] - endedAt[oldest];
        final double tps = size < 2 || span <= 0 ? 0 : (size - 1) * 1e9 / span;
        final double[] sorted = new double[size];
        for (int i = 0; i < size; i++) sorted[i] = durations[(oldest + i) % WINDOW];
        Arrays.sort(sorted);
        return new Snapshot(size, tps, rank(sorted, 0.50), rank(sorted, 0.95), rank(sorted, 0.99));
    }

    /** The nearest rank percentile. */
    private static double rank(double[] sorted, double quantile) {
        return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
    }
}
