package dev.gameengine.event.item;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.CancellableEvent;
import dev.gameengine.event.trait.ItemEvent;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.item.ItemStack;

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
