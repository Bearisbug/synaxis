package dev.gameengine.event.trait;

import dev.gameengine.entity.Player;

/**
 * Represents any event called on a {@link Player}.
 */
public interface PlayerEvent extends EntityEvent {

    /**
     * Gets the player.
     *
     * @return the player
     */
    Player getPlayer();

    /**
     * Returns {@link #getPlayer()}.
     */
    @Override
    default Player getEntity() {
        return getPlayer();
    }
}
