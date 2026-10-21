package io.github.bearisbug.synaxis.event.inventory;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Called when a player interacts with an item in the creative menu
 */
public class CreativeInventoryActionEvent implements PlayerInstanceEvent, CancellableEvent {
    private final Player player;
    private final int slot;
    private ItemStack clickedItem;
    private boolean cancelled;

    public CreativeInventoryActionEvent(Player player,
                                        int slot,
                                        ItemStack clicked) {
        this.player = player;
        this.slot = slot;
        this.clickedItem = clicked;
        this.cancelled = false;
    }

    /**
     * Gets the player who is trying to click on the inventory.
     *
     * @return the player who clicked
     */
    @Override
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the clicked slot number.
     * <p>
     * -1 means the player drops the item out of the creative menu into the world: {@link #getClickedItem()} is the item
     * to drop, and cancelling the event drops nothing. Any other value is an engine slot, numbered like
     * {@link io.github.bearisbug.synaxis.inventory.PlayerInventory#getItemStack(int)}
     * (see {@link io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils}).
     *
     * @return -1 for a drop, otherwise the engine slot of the player inventory
     */
    public int getSlot() {
        return slot;
    }

    /**
     * Gets the item the client asks to put in the slot, or to drop when {@link #getSlot()} is -1. The client builds
     * the item freely, components included: the engine only checks that the player is in creative mode, never the
     * item itself.
     *
     * @return the item the client sent
     */
    public ItemStack getClickedItem() {
        return clickedItem;
    }

    /**
     * Replaces the item that ends up in the slot, or that is dropped.
     *
     * @param clickedItem the item to put in the slot or to drop
     */
    public void setClickedItem(ItemStack clickedItem) {
        this.clickedItem = clickedItem;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
