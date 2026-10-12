package io.github.bearisbug.synaxis.game.playkit;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A countdown on an injected monotonic clock ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}).
 * <p>
 * It takes no lock: one thread uses it, which in a game is the serial part of the tick.
 */
public final class Countdown {

    private final LongSupplier nanoClock;
    private boolean running;
    private long deadline;

    /**
     * Creates a countdown that is not running.
     *
     * @param nanoClock the monotonic clock, in nanoseconds
     */
    public Countdown(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Starts counting down from now, again from the start if it was running.
     *
     * @param length how long to count down
     * @throws IllegalArgumentException if the length is negative
     */
    public void start(Duration length) {
        if (length.isNegative())
            throw new IllegalArgumentException("a countdown does not count down a negative time, not " + length);
        deadline = nanoClock.getAsLong() + length.toNanos();
        running = true;
    }

    /**
     * Stops the countdown.
     */
    public void cancel() {
        running = false;
    }

    /**
     * Whether the countdown was started and not cancelled since; it still is once it reached zero.
     *
     * @return true while started
     */
    public boolean running() {
        return running;
    }

    /**
     * Whether the countdown is running and reached zero.
     *
     * @return true once the time ran out
     */
    public boolean done() {
        return running && deadline - nanoClock.getAsLong() <= 0;
    }

    /**
     * The time left.
     *
     * @return the time until zero, zero when the countdown is not running or ran out
     */
    public Duration remaining() {
        if (!running) return Duration.ZERO;
        final long left = deadline - nanoClock.getAsLong();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }
}
