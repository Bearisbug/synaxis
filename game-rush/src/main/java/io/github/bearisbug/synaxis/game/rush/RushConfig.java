package io.github.bearisbug.synaxis.game.rush;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.function.Function;

/**
 * The five {@code synaxis.rush.*} keys of {@code docs/DESIGN-PLAY.md} §6.7.5, read once when the plugin
 * is enabled ({@code API-PLAY-010}). A value that is not an integer, or out of its range, fails
 * {@code onEnable} with an {@link IllegalArgumentException}, and the host refuses to start.
 *
 * @param minPlayers   how many queued players start a countdown, 1 to {@link #MAX_PLAYERS}
 * @param lobbyWait    how long the lobby counts down, 0 to 3,600 s
 * @param roundLength  how long a round runs, 1 to 3,600 s
 * @param resultLength how long the result shows before everyone is sent back, 1 to 600 s
 * @param targetScore  the score that ends a round, 1 to 1,000,000
 */
record RushConfig(int minPlayers, Duration lobbyWait, Duration roundLength, Duration resultLength, int targetScore) {

    static final String MIN_PLAYERS = "synaxis.rush.min-players";
    static final String LOBBY_WAIT_SECONDS = "synaxis.rush.lobby-wait-seconds";
    static final String ROUND_SECONDS = "synaxis.rush.round-seconds";
    static final String RESULT_SECONDS = "synaxis.rush.result-seconds";
    static final String TARGET_SCORE = "synaxis.rush.target-score";

    /** The most players one round takes (§6.7.2); a larger minimum would never start one. */
    static final int MAX_PLAYERS = 8;

    /**
     * The keys as the system properties give them.
     *
     * @return the configuration
     * @throws IllegalArgumentException if a key is not an integer or out of its range
     */
    static RushConfig fromSystemProperties() {
        return from(System::getProperty);
    }

    /**
     * The keys as {@code properties} gives them, the defaults of §6.7.5 for those it does not.
     *
     * @param properties the value of a key, null when it is not given
     * @return the configuration
     * @throws IllegalArgumentException if a key is not an integer or out of its range
     */
    static RushConfig from(Function<String, @Nullable String> properties) {
        return new RushConfig(
                read(properties, MIN_PLAYERS, 2, 1, MAX_PLAYERS),
                Duration.ofSeconds(read(properties, LOBBY_WAIT_SECONDS, 10, 0, 3_600)),
                Duration.ofSeconds(read(properties, ROUND_SECONDS, 240, 1, 3_600)),
                Duration.ofSeconds(read(properties, RESULT_SECONDS, 15, 1, 600)),
                read(properties, TARGET_SCORE, 150, 1, 1_000_000));
    }

    private static int read(Function<String, @Nullable String> properties, String key, int fallback, int min, int max) {
        final String value = properties.apply(key);
        if (value == null) return fallback;
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + key + "' is not an integer: " + value, e);
        }
        if (parsed < min || parsed > max)
            throw new IllegalArgumentException("Property '" + key + "' is not from " + min + " to " + max + ": " + value);
        return parsed;
    }
}
