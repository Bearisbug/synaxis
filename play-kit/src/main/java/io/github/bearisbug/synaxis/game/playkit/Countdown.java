package io.github.bearisbug.synaxis.game.playkit;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A countdown on an injected monotonic clock ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}); the
 * skeleton of RUN-077 group R.
 */
public final class Countdown {

    public Countdown(LongSupplier nanoClock) {
    }

    public void start(Duration length) {
    }

    public void cancel() {
    }

    public boolean running() {
        return false;
    }

    public boolean done() {
        return false;
    }

    public Duration remaining() {
        return Duration.ZERO;
    }
}
