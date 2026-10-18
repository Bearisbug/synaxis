package io.github.bearisbug.synaxis.timer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CooldownTest {
    @Test
    void testReadySinceBeginning() {
        var cooldown = new Cooldown(Duration.ofSeconds(1));
        assertTrue(cooldown.isReady(0));
        assertTrue(cooldown.isReady(Long.MIN_VALUE));
        assertTrue(cooldown.isReady(Long.MAX_VALUE));
    }
    @Test
    void testConstructorAndIsReady() {
        long beforeNanos = System.nanoTime() - 1;
        var cooldown = new Cooldown(Duration.ofSeconds(1), ChronoUnit.NANOS);
        cooldown.refreshLastUpdate(System.nanoTime());
        long afterNanos = System.nanoTime() + 1;
        assertFalse(cooldown.isReady(beforeNanos + TimeUnit.SECONDS.toNanos(1)));
        assertTrue(cooldown.isReady(afterNanos + TimeUnit.SECONDS.toNanos(1)));
        assertEquals(cooldown.getDuration(), Duration.ofSeconds(1));
    }

    @Test
    void testHasCooldown() {
        long nanoTime = System.nanoTime();
        assertTrue(Cooldown.hasCooldown(ChronoUnit.NANOS, nanoTime, nanoTime - TimeUnit.SECONDS.toNanos(1) + 1, ChronoUnit.SECONDS, 1));
        assertFalse(Cooldown.hasCooldown(ChronoUnit.NANOS, nanoTime, nanoTime - TimeUnit.SECONDS.toNanos(1), ChronoUnit.SECONDS, 1));

        // we assume this test does not take longer than 1 hour
        assertTrue(Cooldown.hasCooldown(nanoTime, ChronoUnit.HOURS, 1));

        assertFalse(Cooldown.hasCooldown(nanoTime - TimeUnit.HOURS.toNanos(1), ChronoUnit.HOURS, 1));
    }

    /** Without a unit, the times are milliseconds, what the engine passes from its tick. */
    @Test
    void theDefaultUnitIsTheMillisecond() {
        var cooldown = new Cooldown(Duration.ofSeconds(1));
        cooldown.refreshLastUpdate(0);
        assertFalse(cooldown.isReady(999));
        assertTrue(cooldown.isReady(1000));
    }
}
