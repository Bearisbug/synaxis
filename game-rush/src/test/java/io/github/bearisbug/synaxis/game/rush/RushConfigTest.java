package io.github.bearisbug.synaxis.game.rush;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The five {@code synaxis.rush.*} keys ({@code docs/DESIGN-PLAY.md} §6.7.5, {@code API-PLAY-010}).
 */
class RushConfigTest {

    @Test
    void theDefaultsAreThoseOfTheDesign() {
        assertEquals(new RushConfig(2, Duration.ofSeconds(10), Duration.ofSeconds(240), Duration.ofSeconds(15), 150),
                RushConfig.from(Map.<String, String>of()::get));
    }

    @Test
    void theValuesOfTheCiRunAreTaken() {
        final Map<String, String> ci = Map.of(RushConfig.MIN_PLAYERS, "1", RushConfig.LOBBY_WAIT_SECONDS, "3",
                RushConfig.ROUND_SECONDS, "45", RushConfig.RESULT_SECONDS, "10", RushConfig.TARGET_SCORE, "150");

        assertEquals(new RushConfig(1, Duration.ofSeconds(3), Duration.ofSeconds(45), Duration.ofSeconds(10), 150),
                RushConfig.from(ci::get));
    }

    @Test
    void theEdgesOfEachRangeAreTaken() {
        final Map<String, String> low = Map.of(RushConfig.MIN_PLAYERS, "1", RushConfig.LOBBY_WAIT_SECONDS, "0",
                RushConfig.ROUND_SECONDS, "1", RushConfig.RESULT_SECONDS, "1", RushConfig.TARGET_SCORE, "1");
        final Map<String, String> high = Map.of(RushConfig.MIN_PLAYERS, "8", RushConfig.LOBBY_WAIT_SECONDS, "3600",
                RushConfig.ROUND_SECONDS, "3600", RushConfig.RESULT_SECONDS, "600", RushConfig.TARGET_SCORE, "1000000");

        assertEquals(new RushConfig(1, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), 1), RushConfig.from(low::get));
        assertEquals(new RushConfig(8, Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(10), 1_000_000),
                RushConfig.from(high::get));
    }

    @Test
    void aValueOutOfItsRangeIsRefusedWithTheKeyAndTheRange() {
        assertRefused(RushConfig.MIN_PLAYERS, "9", "Property 'synaxis.rush.min-players' is not from 1 to 8: 9");
        assertRefused(RushConfig.MIN_PLAYERS, "0", "Property 'synaxis.rush.min-players' is not from 1 to 8: 0");
        assertRefused(RushConfig.LOBBY_WAIT_SECONDS, "-1", "Property 'synaxis.rush.lobby-wait-seconds' is not from 0 to 3600: -1");
        assertRefused(RushConfig.ROUND_SECONDS, "0", "Property 'synaxis.rush.round-seconds' is not from 1 to 3600: 0");
        assertRefused(RushConfig.RESULT_SECONDS, "601", "Property 'synaxis.rush.result-seconds' is not from 1 to 600: 601");
        assertRefused(RushConfig.TARGET_SCORE, "1000001", "Property 'synaxis.rush.target-score' is not from 1 to 1000000: 1000001");
    }

    @Test
    void aValueThatIsNoIntegerIsRefused() {
        assertRefused(RushConfig.ROUND_SECONDS, "4m", "Property 'synaxis.rush.round-seconds' is not an integer: 4m");
        assertRefused(RushConfig.TARGET_SCORE, " 150", "Property 'synaxis.rush.target-score' is not an integer:  150");
    }

    private static void assertRefused(String key, String value, String message) {
        final Map<String, String> properties = Map.of(key, value);
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> RushConfig.from(properties::get)).getMessage());
    }
}
