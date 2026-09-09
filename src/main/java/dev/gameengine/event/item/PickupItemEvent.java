package dev.gameengine.event.item;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.ItemEntity;
import dev.gameengine.entity.LivingEntity;
import dev.gameengine.event.trait.CancellableEvent;
import dev.gameengine.event.trait.EntityInstanceEvent;
import dev.gameengine.event.trait.ItemEvent;
import dev.gameengine.item.ItemStack;

public class PickupItemEvent implements EntityInstanceEvent, ItemEvent, CancellableEvent {

    private final LivingEntity livingEntity;
    private final ItemEntity itemEntity;

    private boolean cancelled;

    public PickupItemEvent(LivingEntity livingEntity, ItemEntity itemEntity) {
        this.livingEntity = livingEntity;
        this.itemEntity = itemEntity;
    }

    public LivingEntity getLivingEntity() {
        return livingEntity;
    }

    public ItemEntity getItemEntity() {
        return itemEntity;
    }

    @Override
    public ItemStack getItemStack() {
        return getItemEntity().getItemStack();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public Entity getEntity() {
        return livingEntity;
    }
}
