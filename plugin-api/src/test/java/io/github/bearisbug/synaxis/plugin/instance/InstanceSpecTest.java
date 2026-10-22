package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a managed instance is built from ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}): a name
 * matching {@code [a-z0-9_]{1,32}}, a chunk radius of 0 to 16 and a settle timeout above zero, all
 * checked when the spec is built, so that a spec {@code create} cannot take never exists; and the
 * defaults of the short constructor.
 */
class InstanceSpecTest {

    private static InstanceSpec spec(String name, int chunkRadius, Duration settleTimeout) {
        return new InstanceSpec(name, PluginApi.MATCH_DIMENSION_TYPE, chunkRadius, _ -> { }, settleTimeout,
                _ -> new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"round", "lobby_2", "0", "abcdefghijklmnopqrstuvwxyz012345"})
    void aNameOfLowerCaseLettersDigitsAndUnderscoresIsTaken(String name) {
        assertEquals(name, spec(name, 2, Duration.ofSeconds(30)).name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Round", "round-1", "rush:round", "abcdefghijklmnopqrstuvwxyz0123456", "回合"})
    void anyOtherNameIsRefused(String name) {
        assertThrows(IllegalArgumentException.class, () -> spec(name, 2, Duration.ofSeconds(30)));
    }

    @Test
    void theChunkRadiusIsZeroTo16() {
        assertEquals(0, spec("round", 0, Duration.ofSeconds(30)).chunkRadius());
        assertEquals(16, spec("round", 16, Duration.ofSeconds(30)).chunkRadius());

        assertThrows(IllegalArgumentException.class, () -> spec("round", -1, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> spec("round", 17, Duration.ofSeconds(30)));
    }

    @Test
    void theSettleTimeoutIsAboveZero() {
        assertEquals(Duration.ofMillis(1), spec("round", 2, Duration.ofMillis(1)).settleTimeout());

        assertThrows(IllegalArgumentException.class, () -> spec("round", 2, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> spec("round", 2, Duration.ofSeconds(-1)));
    }

    @Test
    void theShortConstructorBuildsOnTheMatchTypeSettlesIn30SecondsAndKicks() {
        final InstanceSpec spec = new InstanceSpec("round", 2, _ -> { });

        assertEquals(PluginApi.MATCH_DIMENSION_TYPE, spec.dimensionType());
        assertEquals(Duration.ofSeconds(30), spec.settleTimeout());
        // The default exit is the same for every player, so it is asked without one
        assertEquals(new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE), spec.fallbackExit().apply(null));
        assertThrows(IllegalArgumentException.class, () -> new InstanceSpec("Round", 2, _ -> { }));
    }
}
