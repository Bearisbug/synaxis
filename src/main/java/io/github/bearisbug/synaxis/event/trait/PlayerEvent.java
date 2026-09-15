package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.entity.Player;

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
