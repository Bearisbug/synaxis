package dev.gameengine.event.trait;

import dev.gameengine.event.Event;
import dev.gameengine.item.ItemStack;

/**
 * Represents any event called about an {@link ItemStack}.
 */
public interface ItemEvent extends Event {
    ItemStack getItemStack();
}
