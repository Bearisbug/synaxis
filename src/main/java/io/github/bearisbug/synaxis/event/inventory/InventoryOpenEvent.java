package io.github.bearisbug.synaxis.event.inventory;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.Objects;

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
     * @return the inventory to open, never null: to open nothing, cancel the event (and call
     * {@link Player#closeInventory()} to close the current one)
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
     * @param inventory the inventory to open, an {@link Inventory}
     * @throws NullPointerException     if {@code inventory} is null
     * @throws IllegalArgumentException if {@code inventory} is not an {@link Inventory}, such as a player inventory
     */
    public void setInventory(AbstractInventory inventory) {
        Objects.requireNonNull(inventory, "the inventory to open; cancel the event to open nothing");
        Check.argCondition(!(inventory instanceof Inventory), "InventoryOpenEvent can only open a container Inventory, got "
                + inventory.getClass().getSimpleName() + "; cancel the event to open nothing");
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
