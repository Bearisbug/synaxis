package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Event when an item is used without clicking on a block.
 */
public class PlayerUseItemEvent implements PlayerInstanceEvent, ItemEvent, CancellableEvent {

    private final Player player;
    private final PlayerHand hand;
    private final ItemStack itemStack;

    private long itemUseTime;
    private boolean cancelled;

    public PlayerUseItemEvent(Player player, PlayerHand hand, ItemStack itemStack, long itemUseTime) {
        this.player = player;
        this.hand = hand;
        this.itemStack = itemStack;
        this.itemUseTime = itemUseTime;
    }

    /**
     * Gets which hand the player used.
     *
     * @return the hand used
     */
    public PlayerHand getHand() {
        return hand;
    }

    /**
     * Gets the item which has been used.
     *
     * @return the item
     */
    @Override
    public ItemStack getItemStack() {
        return itemStack;
    }

    /**
     * Gets the item usage duration. After this amount of milliseconds,
     * the animation will stop automatically and {@link io.github.bearisbug.synaxis.event.item.PlayerFinishItemUseEvent} is called.
     *
     * @return the item use time
     */
    public long getItemUseTime() {
        return itemUseTime;
    }

    /**
     * Changes the item usage duration.
     *
     * @param itemUseTime the new item use time
     */
    public void setItemUseTime(long itemUseTime) {
        this.itemUseTime = itemUseTime;
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
