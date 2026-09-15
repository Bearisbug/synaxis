package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when a player stop flying.
 */
public class PlayerStopFlyingEvent implements PlayerInstanceEvent {

    private final Player player;

    public PlayerStopFlyingEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
