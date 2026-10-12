package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A game class that writes one line per action a client sends, which is what
 * {@code ArchitectureTest#gameSidePathsDoNotWriteALinePerPacket} exists to stop.
 * <p>
 * It is only ever imported as a synthetic {@code JavaClasses}: {@code ImportOption.DoNotIncludeTests}
 * keeps it out of the scanned set, so it proves the rule fires without turning the build red.
 */
final class UnboundedRushLogFixture {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnboundedRushLogFixture.class);

    private UnboundedRushLogFixture() {
    }

    static void queue(Player player) {
        LOGGER.warn("Queue key pressed: connection_id={}", player.getPlayerConnection().connectionId());
    }
}
