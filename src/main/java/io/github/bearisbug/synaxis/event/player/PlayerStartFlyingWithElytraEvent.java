package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

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
