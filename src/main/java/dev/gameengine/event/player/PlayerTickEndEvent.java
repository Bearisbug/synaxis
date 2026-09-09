package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

public class PlayerTickEndEvent implements PlayerInstanceEvent {
    private final Player player;

    public PlayerTickEndEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
