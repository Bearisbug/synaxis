package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

/**
 * Called when a player disconnect.
 */
public class PlayerDisconnectEvent implements PlayerInstanceEvent {

    private final Player player;

    public PlayerDisconnectEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
