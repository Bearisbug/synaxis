package io.github.bearisbug.synaxis.event.item;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemAnimation;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.utils.validate.Check;

/**
 * Called when a player begins using an item with the item, animation, and duration.
 *
 * <p>Setting the use duration to zero or cancelling the event will prevent consumption.</p>
 */
public class PlayerBeginItemUseEvent implements PlayerInstanceEvent, ItemEvent, CancellableEvent {
    private final Player player;
    private final PlayerHand hand;
    private final ItemStack itemStack;
    private final ItemAnimation animation;
    private long itemUseDuration;

    private boolean cancelled = false;

    public PlayerBeginItemUseEvent(Player player, PlayerHand hand,
                                   ItemStack itemStack, ItemAnimation animation,
                                   long itemUseDuration) {
        this.player = player;
        this.hand = hand;
        this.itemStack = itemStack;
        this.animation = animation;
        this.itemUseDuration = itemUseDuration;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public PlayerHand getHand() {
        return hand;
    }

    @Override
    public ItemStack getItemStack() {
        return itemStack;
    }

    public ItemAnimation getAnimation() {
        return animation;
    }

    /**
     * Returns the item use duration, in ticks. A duration of zero will prevent consumption (same effect as cancellation).
     *
     * @return the current item use duration
     */
    public long getItemUseDuration() {
        return itemUseDuration;
    }

    /**
     * Sets the item use duration, in ticks.
     */
    public void setItemUseDuration(long itemUseDuration) {
        Check.argCondition(itemUseDuration < 0, "Item use duration cannot be negative");
        this.itemUseDuration = itemUseDuration;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
