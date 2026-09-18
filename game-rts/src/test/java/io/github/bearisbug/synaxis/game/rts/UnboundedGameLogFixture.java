package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A game class that writes one line per inbound payload, which is what
 * {@code ArchitectureTest#gameSidePathsDoNotWriteALinePerPacket} exists to stop.
 * <p>
 * It is only ever imported as a synthetic {@code JavaClasses}: {@code ImportOption.DoNotIncludeTests}
 * keeps it out of the scanned set, so it proves the rule fires without turning the build red.
 */
final class UnboundedGameLogFixture {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnboundedGameLogFixture.class);

    private UnboundedGameLogFixture() {
    }

    static void receive(Player player, byte[] payload) {
        LOGGER.warn("Dropped a game payload: connection_id={} bytes={}",
                player.getPlayerConnection().connectionId(), payload.length);
    }
}
