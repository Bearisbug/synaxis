package io.github.bearisbug.synaxis.event.inventory;

import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.event.trait.RecursiveEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Called when {@link AbstractInventory#setItemStack(int, ItemStack)} has changed a slot.
 * This event cannot be cancelled and the slot already holds {@link #getNewItem()}.
 * <p>
 * A change made by a click is dispatched once the whole click, cursor included, has been committed and no inventory
 * monitor is held; any other change is dispatched right after the slot is written (see {@link AbstractInventory}).
 */
public class InventoryItemChangeEvent implements InventoryEvent, RecursiveEvent {

    private final AbstractInventory inventory;
    private final int slot;
    private final ItemStack previousItem;
    private final ItemStack newItem;

    public InventoryItemChangeEvent(AbstractInventory inventory, int slot,
                                    ItemStack previousItem, ItemStack newItem) {
        this.inventory = inventory;
        this.slot = slot;
        this.previousItem = previousItem;
        this.newItem = newItem;
    }

    /**
     * Gets the changed slot number.
     *
     * @return the changed slot number.
     */
    public int getSlot() {
        return slot;
    }

    /**
     * Gets a previous item that was on changed slot.
     *
     * @return a previous item that was on changed slot.
     */
    public ItemStack getPreviousItem() {
        return previousItem;
    }

    /**
     * Gets the item now in the changed slot, the one the inventory stored.
     *
     * @return the item now in the changed slot
     */
    public ItemStack getNewItem() {
        return newItem;
    }

    @Override
    public AbstractInventory getInventory() {
        return inventory;
    }
}
