package io.github.bearisbug.synaxis.game.playkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Countdown} on a hand driven clock ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}).
 */
class CountdownTest {

    private final AtomicLong clock = new AtomicLong(5_000_000_000L);
    private final Countdown countdown = new Countdown(clock::get);

    @Test
    void aNewCountdownIsNotRunning() {
        assertFalse(countdown.running());
        assertFalse(countdown.done());
        assertEquals(Duration.ZERO, countdown.remaining());
    }

    @Test
    void itCountsDownToZeroAndStaysThere() {
        countdown.start(Duration.ofSeconds(10));
        assertTrue(countdown.running());
        assertEquals(Duration.ofSeconds(10), countdown.remaining());
        assertFalse(countdown.done());

        advance(Duration.ofSeconds(4));
        assertEquals(Duration.ofSeconds(6), countdown.remaining());
        assertFalse(countdown.done());

        advance(Duration.ofSeconds(6));
        assertTrue(countdown.done());
        assertTrue(countdown.running(), "a countdown that ran out stopped running by itself");
        advance(Duration.ofSeconds(1));
        assertEquals(Duration.ZERO, countdown.remaining());
        assertTrue(countdown.done());
    }

    @Test
    void aCancelledCountdownIsNeitherRunningNorDone() {
        countdown.start(Duration.ofSeconds(10));
        advance(Duration.ofSeconds(10));

        countdown.cancel();

        assertFalse(countdown.running());
        assertFalse(countdown.done());
        assertEquals(Duration.ZERO, countdown.remaining());
    }

    @Test
    void startingAgainStartsFromTheTop() {
        countdown.start(Duration.ofSeconds(10));
        advance(Duration.ofSeconds(8));

        countdown.start(Duration.ofSeconds(10));

        assertEquals(Duration.ofSeconds(10), countdown.remaining());
    }

    @Test
    void aCountdownOfZeroIsDoneAtOnceAndANegativeOneIsRefused() {
        countdown.start(Duration.ZERO);
        assertTrue(countdown.done());

        assertThrows(IllegalArgumentException.class, () -> countdown.start(Duration.ofMillis(-1)));
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }
}
