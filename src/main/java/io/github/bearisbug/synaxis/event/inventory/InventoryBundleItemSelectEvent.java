package io.github.bearisbug.synaxis.event.inventory;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;

/**
 * Called when a player selects an item from a Bundle
 */
public class InventoryBundleItemSelectEvent implements InventoryEvent, PlayerInstanceEvent {

    private final AbstractInventory inventory;
    private final Player player;
    private final int slot;
    private final int selectedItemIndex;

    public InventoryBundleItemSelectEvent(Player player, AbstractInventory inventory, int slot, int selectedItemIndex) {
        this.player = player;
        this.inventory = inventory;
        this.slot = slot;
        this.selectedItemIndex = selectedItemIndex;
    }

    /**
     * Gets the player who selected the item
     *
     * @return the player
     */
    @Override
    public Player getPlayer() {
        return player;
    }

    /**
     * Returns the modified slot, relative to {@link #getInventory()}
     */
    public int getSlot() {
        return this.slot;
    }

    /**
     * Returns the index of the selected item in the bundle. -1 if the bundle is unhovered and the
     * current item is deselected
     */
    public int getSelectedItemIndex() {
        return this.selectedItemIndex;
    }

    @Override
    public AbstractInventory getInventory() {
        return inventory;
    }
}
