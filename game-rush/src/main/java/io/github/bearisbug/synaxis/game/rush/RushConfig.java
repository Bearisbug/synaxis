package io.github.bearisbug.synaxis.game.rush;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.function.Function;

/**
 * The five {@code synaxis.rush.*} keys ({@code docs/DESIGN-PLAY.md} §6.7.5); the skeleton of RUN-077
 * group R.
 *
 * @param minPlayers   how many queued players start a countdown
 * @param lobbyWait    how long the lobby counts down
 * @param roundLength  how long a round runs
 * @param resultLength how long the result shows
 * @param targetScore  the score that ends a round
 */
record RushConfig(int minPlayers, Duration lobbyWait, Duration roundLength, Duration resultLength, int targetScore) {

    static final String MIN_PLAYERS = "synaxis.rush.min-players";
    static final String LOBBY_WAIT_SECONDS = "synaxis.rush.lobby-wait-seconds";
    static final String ROUND_SECONDS = "synaxis.rush.round-seconds";
    static final String RESULT_SECONDS = "synaxis.rush.result-seconds";
    static final String TARGET_SCORE = "synaxis.rush.target-score";
    static final int MAX_PLAYERS = 8;

    static RushConfig fromSystemProperties() {
        return from(System::getProperty);
    }

    static RushConfig from(Function<String, @Nullable String> properties) {
        return new RushConfig(2, Duration.ofSeconds(10), Duration.ofSeconds(240), Duration.ofSeconds(15), 150);
    }
}
