package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

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
