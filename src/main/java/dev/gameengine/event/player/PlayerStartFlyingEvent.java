package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

/**
 * Called when a player start flying.
 */
public class PlayerStartFlyingEvent implements PlayerInstanceEvent {

    private final Player player;

    public PlayerStartFlyingEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
