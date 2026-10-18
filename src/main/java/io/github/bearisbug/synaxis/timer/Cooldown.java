package io.github.bearisbug.synaxis.timer;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;

public final class Cooldown {
    private final Duration duration;
    private final TemporalUnit temporalUnit;
    // if this cooldown object has a lastUpdate set
    private boolean hasLastUpdate;
    private long lastUpdate;

    /**
     * Creates a cooldown with a measurement unit of {@link ChronoUnit#MILLIS}
     */
    public Cooldown(Duration duration) {
        this(duration, ChronoUnit.MILLIS);
    }

    /**
     * Creates a cooldown with a given unit of measurement.
     * <p>
     * All calls to {@link #refreshLastUpdate(long)} and {@link #isReady(long)} must pass values in the given unit.
     *
     * @param duration     the duration of the cooldown
     * @param temporalUnit the unit of measurement
     */
    public Cooldown(Duration duration, TemporalUnit temporalUnit) {
        this.duration = duration;
        this.temporalUnit = temporalUnit;
        this.hasLastUpdate = false;
    }

    /**
     * {@return the unit of measurement}
     */
    public TemporalUnit getTemporalUnit() {
        return temporalUnit;
    }

    public Duration getDuration() {
        return this.duration;
    }

    /**
     * Refreshes the time of the last update.
     *
     * @param lastUpdate the time of the last update, in this cooldown's temporal unit (MILLIS unless
     *                   another unit was given to the constructor)
     */
    public void refreshLastUpdate(long lastUpdate) {
        this.hasLastUpdate = true;
        this.lastUpdate = lastUpdate;
    }

    /**
     * Checks if the cooldown is ready again
     *
     * @param time the time, in this cooldown's temporal unit (MILLIS unless another unit was given to
     *             the constructor)
     */
    public boolean isReady(long time) {
        if (!hasLastUpdate) return true;
        return !hasCooldown(temporalUnit, time, lastUpdate, duration);
    }

    /**
     * Gets if something is in cooldown based on a {@code currentTime}.
     *
     * @param currentTime  the current time in milliseconds
     * @param lastUpdate   the last update in milliseconds
     * @param cooldownUnit the time unit of the cooldown
     * @param cooldown     the value of the cooldown
     * @return true if the cooldown is in progress, false otherwise
     */
    public static boolean hasCooldown(long currentTime, long lastUpdate, TemporalUnit cooldownUnit, long cooldown) {
        return hasCooldown(currentTime, lastUpdate, Duration.of(cooldown, cooldownUnit));
    }

    /**
     * Gets if something is in cooldown based on a {@code currentTime}.
     *
     * @param currentTime the current time in milliseconds
     * @param lastUpdate  the last update in milliseconds
     * @param duration    the cooldown
     * @return true if the cooldown is in progress, false otherwise
     */
    public static boolean hasCooldown(long currentTime, long lastUpdate, Duration duration) {
        return hasCooldown(ChronoUnit.MILLIS, currentTime, lastUpdate, duration);
    }

    /**
     * Gets if something is in cooldown based on a {@code currentTime}.
     *
     * @param temporalUnit the {@link TemporalUnit} of {@code currentTime} and {@code lastUpdate}
     * @param currentTime  the current time in {@code temporalUnit}
     * @param lastUpdate   the last update in {@code temporalUnit}
     * @param cooldownUnit the time unit of the cooldown
     * @param cooldown     the value of the cooldown
     * @return true if the cooldown is in progress, false otherwise
     */
    public static boolean hasCooldown(TemporalUnit temporalUnit, long currentTime, long lastUpdate, TemporalUnit cooldownUnit, long cooldown) {
        return hasCooldown(temporalUnit, currentTime, lastUpdate, Duration.of(cooldown, cooldownUnit));
    }

    /**
     * Gets if something is in cooldown based on a {@code currentTime}.
     *
     * @param temporalUnit the {@link TemporalUnit} of {@code currentTime} and {@code lastUpdate}
     * @param currentTime  the current time in the given {@code temporalUnit}
     * @param lastUpdate   the last update in the given {@code temporalUnit}
     * @param duration     the cooldown
     * @return true if the cooldown is in progress, false otherwise
     */
    public static boolean hasCooldown(TemporalUnit temporalUnit, long currentTime, long lastUpdate, Duration duration) {
        return Duration.of(currentTime - lastUpdate, temporalUnit).compareTo(duration) < 0;
    }

    /**
     * Gets if something is in cooldown based on the current time, which is {@link System#nanoTime()}:
     * {@code lastUpdateNanos} must come from {@link System#nanoTime()} too.
     *
     * @param lastUpdateNanos the last update, a {@link System#nanoTime()}
     * @param temporalUnit    the time unit of the cooldown
     * @param cooldown        the value of the cooldown
     * @return true if the cooldown is in progress, false otherwise
     */
    public static boolean hasCooldown(long lastUpdateNanos, TemporalUnit temporalUnit, int cooldown) {
        return hasCooldown(ChronoUnit.NANOS, System.nanoTime(), lastUpdateNanos, temporalUnit, cooldown);
    }
}
