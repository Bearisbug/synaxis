package dev.gameengine.event.trait;

import dev.gameengine.event.Event;
import dev.gameengine.inventory.AbstractInventory;

/**
 * Represents any event inside an {@link AbstractInventory}.
 */
public interface InventoryEvent extends Event {

    /**
     * Gets the inventory that was clicked.
     */
    AbstractInventory getInventory();
}
