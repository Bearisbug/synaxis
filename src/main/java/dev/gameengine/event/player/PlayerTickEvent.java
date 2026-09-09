package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

/**
 * Called at each player tick.
 */
public class PlayerTickEvent implements PlayerInstanceEvent {

    private final Player player;

    public PlayerTickEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
