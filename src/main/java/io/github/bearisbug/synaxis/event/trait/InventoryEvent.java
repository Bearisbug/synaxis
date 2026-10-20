package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;

/**
 * Represents any event inside an {@link AbstractInventory}.
 */
public interface InventoryEvent extends Event {

    /**
     * Gets the inventory this event is about.
     */
    AbstractInventory getInventory();
}
