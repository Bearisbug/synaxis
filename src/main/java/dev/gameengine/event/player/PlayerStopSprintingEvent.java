package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

/**
 * Called when a player stops sprinting.
 */
public class PlayerStopSprintingEvent implements PlayerInstanceEvent {

    private final Player player;

    public PlayerStopSprintingEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
