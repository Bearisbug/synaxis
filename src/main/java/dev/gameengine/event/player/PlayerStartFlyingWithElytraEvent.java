package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

public class PlayerStartFlyingWithElytraEvent implements PlayerInstanceEvent {

    private final Player player;

    public PlayerStartFlyingWithElytraEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
