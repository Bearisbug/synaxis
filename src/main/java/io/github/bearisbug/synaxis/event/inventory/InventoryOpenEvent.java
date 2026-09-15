package io.github.bearisbug.synaxis.event.inventory;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.Inventory;

/**
 * Called when a player open an {@link AbstractInventory}.
 * <p>
 * Executed by {@link Player#openInventory(Inventory)}.
 */
public class InventoryOpenEvent implements InventoryEvent, PlayerInstanceEvent, CancellableEvent {

    private AbstractInventory inventory;
    private final Player player;

    private boolean cancelled;

    public InventoryOpenEvent(AbstractInventory inventory, Player player) {
        this.inventory = inventory;
        this.player = player;
    }

    /**
     * Gets the player who opens the inventory.
     *
     * @return the player who opens the inventory
     */
    @Override
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the inventory to open, this could have been change by the {@link #setInventory(AbstractInventory)}.
     *
     * @return the inventory to open, null to just close the current inventory if any
     */
    @Override
    public AbstractInventory getInventory() {
        return inventory;
    }

    /**
     * Changes the inventory to open.
     * <p>
     * To do not open any inventory see {@link #setCancelled(boolean)}.
     *
     * @param inventory the inventory to open
     */
    public void setInventory(AbstractInventory inventory) {
        this.inventory = inventory;
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
