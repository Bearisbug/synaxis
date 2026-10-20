package io.github.bearisbug.synaxis.utils;

import io.github.bearisbug.synaxis.utils.validate.Check;

import java.time.Duration;

/**
 * Tick related utilities.
 */
public final class TickUtils {
    /**
     * Number of ticks per second for the default Java-edition client.
     */
    public static final int CLIENT_TPS = 20;

    /**
     * Length of time per tick for the default Java-edition client.
     */
    public static final int CLIENT_TICK_MS = 50;

    /**
     * Creates a number of ticks from a given duration, rounded down: 49 ms make 0 ticks of 50 ms.
     *
     * @param duration  the duration
     * @param msPerTick the number of milliseconds per tick
     * @return the number of ticks, {@link Integer#MAX_VALUE} for a duration of more ticks than an int holds
     * @throws IllegalArgumentException if duration is negative or msPerTick is not positive
     */
    public static int fromDuration(Duration duration, int msPerTick) {
        Check.argCondition(msPerTick <= 0, "msPerTick must be positive: {0}", msPerTick);
        Check.argCondition(duration.isNegative(), "Duration cannot be negative");
        if (duration.compareTo(Duration.ofMillis((long) Integer.MAX_VALUE * msPerTick)) >= 0) return Integer.MAX_VALUE;
        return (int) (duration.toMillis() / msPerTick);
    }
}
