package io.github.bearisbug.synaxis.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-PROD-009: the chat token bucket of one connection (docs/DESIGN-PROD.md API-PROD-008).
 */
public class ChatRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    public void aBurstOfThreeThenOneASecond() {
        final ChatRateLimiter limiter = new ChatRateLimiter(1, 3);
        final long start = 123_456_789L;
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire(start + i * (SECOND / 10))) accepted++;
        }
        assertEquals(3, accepted, "10 messages within 1 s: the burst of 3 goes through, the rest is dropped");
        assertEquals(7, limiter.dropped());

        // The first token came back 1 s after the first message, and only one did.
        final long later = start + SECOND + SECOND / 10;
        assertTrue(limiter.tryAcquire(later));
        assertFalse(limiter.tryAcquire(later));
        assertEquals(8, limiter.dropped());
    }

    @Test
    public void aSteadySenderAtTheRateIsNeverDropped() {
        final ChatRateLimiter limiter = new ChatRateLimiter(1, 3);
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.tryAcquire(i * SECOND), "message " + i);
        }
        assertEquals(0, limiter.dropped());
    }

    @Test
    public void anIdleConnectionDoesNotSaveUpMoreThanTheBurst() {
        final ChatRateLimiter limiter = new ChatRateLimiter(2, 3);
        final long afterAnHour = 3_600 * SECOND;
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire(afterAnHour)) accepted++;
        }
        assertEquals(3, accepted);
        // At 2 a second the next token is back after half a second.
        assertTrue(limiter.tryAcquire(afterAnHour + SECOND / 2));
    }

    @Test
    public void negativeNanoTimesWork() {
        // System.nanoTime() has an arbitrary origin and may well be negative.
        final ChatRateLimiter limiter = new ChatRateLimiter(1, 1);
        assertTrue(limiter.tryAcquire(-5 * SECOND));
        assertFalse(limiter.tryAcquire(-5 * SECOND + 1));
        assertTrue(limiter.tryAcquire(-4 * SECOND));
    }
}
