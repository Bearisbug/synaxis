package io.github.bearisbug.synaxis.event.item;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

public class ItemDropEvent implements PlayerInstanceEvent, ItemEvent, CancellableEvent {

    private final Player player;
    private final ItemStack itemStack;

    private boolean cancelled;

    public ItemDropEvent(Player player, ItemStack itemStack) {
        this.player = player;
        this.itemStack = itemStack;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public ItemStack getItemStack() {
        return itemStack;
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
