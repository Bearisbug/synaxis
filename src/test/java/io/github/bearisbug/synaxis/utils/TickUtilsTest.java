package io.github.bearisbug.synaxis.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TickUtilsTest {

    @Test
    void aDurationBeyondAnIntOfTicksSaturates() {
        assertEquals(Integer.MAX_VALUE, TickUtils.fromDuration(Duration.ofDays(36_500), TickUtils.CLIENT_TICK_MS));
        assertEquals(Integer.MAX_VALUE, TickUtils.fromDuration(Duration.ofSeconds(Long.MAX_VALUE), TickUtils.CLIENT_TICK_MS));
    }

    @Test
    void ticksAreRoundedDown() {
        assertEquals(0, TickUtils.fromDuration(Duration.ofMillis(49), TickUtils.CLIENT_TICK_MS));
        assertEquals(1, TickUtils.fromDuration(Duration.ofMillis(50), TickUtils.CLIENT_TICK_MS));
        assertEquals(2, TickUtils.fromDuration(Duration.ofMillis(120), TickUtils.CLIENT_TICK_MS));
    }

    @Test
    void aTickThatIsNotPositiveIsRejected() {
        final Duration second = Duration.ofSeconds(1);
        assertEquals("msPerTick must be positive: 0",
                assertThrows(IllegalArgumentException.class, () -> TickUtils.fromDuration(second, 0)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> TickUtils.fromDuration(second, -1));
        assertThrows(IllegalArgumentException.class, () -> TickUtils.fromDuration(Duration.ofMillis(-1), TickUtils.CLIENT_TICK_MS));
    }
}
