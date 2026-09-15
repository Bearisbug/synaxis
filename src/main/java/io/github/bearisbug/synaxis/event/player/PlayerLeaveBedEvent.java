package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

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
