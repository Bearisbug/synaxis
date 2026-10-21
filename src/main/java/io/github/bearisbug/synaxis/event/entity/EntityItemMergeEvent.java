package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Called when two {@link ItemEntity} are merging their {@link ItemStack} together to form a sole entity.
 */
public class EntityItemMergeEvent implements EntityInstanceEvent, CancellableEvent {

    private final ItemEntity entity;
    private final ItemEntity merged;
    private ItemStack result;

    private boolean cancelled;

    public EntityItemMergeEvent(ItemEntity source, ItemEntity merged, ItemStack result) {
        this.entity = source;
        this.merged = merged;
        this.result = result;
    }

    /**
     * Gets the {@link ItemEntity} who is receiving {@link #getMerged()}.
     * <p>
     * This can be used to get the final ItemEntity position.
     *
     * @return the source ItemEntity
     */
    @Override
    public ItemEntity getEntity() {
        return entity;
    }

    /**
     * Gets the entity who will be merged.
     * <p>
     * This entity will be removed after the event.
     *
     * @return the merged ItemEntity
     */
    public ItemEntity getMerged() {
        return merged;
    }

    /**
     * Gets the final item stack on the ground.
     *
     * @return the item stack
     */
    public ItemStack getResult() {
        return result;
    }

    /**
     * Changes the item stack which will appear on the ground.
     *
     * @param result the new item stack
     */
    public void setResult(ItemStack result) {
        this.result = result;
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
