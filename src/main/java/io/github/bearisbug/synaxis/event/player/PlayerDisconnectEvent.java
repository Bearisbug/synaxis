package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when a player disconnect.
 * <p>
 * Dispatched on the player's tick thread for a player that spawned, on the tick scheduler thread for
 * one that reached the play state without spawning, and on whichever thread drops the connection
 * for one that leaves before the play state; in the last two the player has no instance, so no
 * instance scoped listener gets it. State it shares with code on the tick threads needs a concurrent
 * structure or a lock.
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
