package io.github.bearisbug.synaxis.app.command;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@code tps} reports ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}): TPS from when the
 * ticks of the window ended, MSPT p50 / p95 / p99 by nearest rank over the last
 * {@value TickStats#WINDOW} ticks.
 */
class TickStatsTest {

    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    @Test
    void anEmptyWindowIsZero() {
        assertEquals(new TickStats.Snapshot(0, 0, 0, 0, 0), new TickStats().snapshot());
    }

    @Test
    void percentilesAreNearestRankAndTpsFollowsTheTickEnds() {
        final TickStats stats = new TickStats();
        // 1 ms to 100 ms, in an order that is not sorted
        for (int i = 0; i < 100; i++) stats.record(i * TICK_NANOS, (i * 37) % 100 + 1);
        final TickStats.Snapshot snapshot = stats.snapshot();
        assertEquals(100, snapshot.ticks());
        assertEquals(20.0, snapshot.tps(), 1e-9);
        assertEquals(50.0, snapshot.p50());
        assertEquals(95.0, snapshot.p95());
        assertEquals(99.0, snapshot.p99());
    }

    @Test
    void theWindowForgetsTheOldestTicks() {
        final TickStats stats = new TickStats();
        // 100 slow ticks at 10 TPS, then a full window of 2 ms ticks at 20 TPS
        long now = 0;
        for (int i = 0; i < 100; i++) stats.record(now += 2 * TICK_NANOS, 1_000);
        for (int i = 0; i < TickStats.WINDOW; i++) stats.record(now += TICK_NANOS, 2);
        final TickStats.Snapshot snapshot = stats.snapshot();
        assertEquals(TickStats.WINDOW, snapshot.ticks());
        assertEquals(20.0, snapshot.tps(), 1e-9);
        assertEquals(2.0, snapshot.p99(), "the slow ticks are out of the window");
    }
}
