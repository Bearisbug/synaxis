package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when the player swings his hand.
 */
public class PlayerHandAnimationEvent implements PlayerInstanceEvent, CancellableEvent {

    private final Player player;
    private final PlayerHand hand;

    private boolean cancelled;

    public PlayerHandAnimationEvent(Player player, PlayerHand hand) {
        this.player = player;
        this.hand = hand;
    }

    /**
     * Gets the hand used.
     *
     * @return the hand
     */
    public PlayerHand getHand() {
        return hand;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
