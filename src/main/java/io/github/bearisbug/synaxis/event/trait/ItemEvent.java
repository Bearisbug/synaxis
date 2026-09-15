package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Represents any event called about an {@link ItemStack}.
 */
public interface ItemEvent extends Event {
    ItemStack getItemStack();
}
