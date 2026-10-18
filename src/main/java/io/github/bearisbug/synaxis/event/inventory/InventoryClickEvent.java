package io.github.bearisbug.synaxis.event.inventory;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.click.ClickType;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Called after {@link InventoryPreClickEvent}, once per slot the click acted on; this event cannot be cancelled.
 * <p>
 * It is dispatched on the thread that handled the click (the player's tick thread for a click sent by the client) once
 * the whole click, cursor included, has been committed and no inventory monitor is held, so the inventories and the
 * cursor already show the result of the click.
 */
public class InventoryClickEvent implements InventoryEvent, PlayerInstanceEvent {

    private final AbstractInventory inventory;
    private final Player player;
    private final int slot;
    private final ClickType clickType;
    private final ItemStack clickedItem;
    private final ItemStack cursorItem;

    public InventoryClickEvent(AbstractInventory inventory, Player player,
                               int slot, ClickType clickType,
                               ItemStack clicked, ItemStack cursor) {
        this.inventory = inventory;
        this.player = player;
        this.slot = slot;
        this.clickType = clickType;
        this.clickedItem = clicked;
        this.cursorItem = cursor;
    }

    /**
     * Gets the player who clicked in the inventory.
     *
     * @return the player who clicked in the inventory
     */
    @Override
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the clicked slot number.
     *
     * @return the clicked slot number
     */
    public int getSlot() {
        return slot;
    }

    /**
     * Gets the click type.
     *
     * @return the click type
     */
    public ClickType getClickType() {
        return clickType;
    }

    /**
     * Gets the clicked item.
     *
     * @return the clicked item
     */
    public ItemStack getClickedItem() {
        return clickedItem;
    }

    /**
     * Gets the item in the player cursor.
     *
     * @return the cursor item
     */
    public ItemStack getCursorItem() {
        return cursorItem;
    }

    @Override
    public AbstractInventory getInventory() {
        return inventory;
    }
}
