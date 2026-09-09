package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.CancellableEvent;
import dev.gameengine.event.trait.PlayerInstanceEvent;

public class PlayerLeaveBedEvent implements CancellableEvent, PlayerInstanceEvent {

    private final Player player;
    private boolean cancelled = false;

    public PlayerLeaveBedEvent(Player player) {
        this.player = player;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

}
