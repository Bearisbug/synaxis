package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

public class PlayerStopFlyingWithElytraEvent implements PlayerInstanceEvent {

    private final Player player;

    public PlayerStopFlyingWithElytraEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
