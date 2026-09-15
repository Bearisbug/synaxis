package io.github.bearisbug.synaxis.event.item;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

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
