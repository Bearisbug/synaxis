package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when a player indicates that they have finished loading into the world.
 *
 * <p>This is driven by the client so should be considered as such.</p>
 */
public class PlayerLoadedEvent implements PlayerInstanceEvent {
    private final Player player;

    public PlayerLoadedEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
