package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

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
