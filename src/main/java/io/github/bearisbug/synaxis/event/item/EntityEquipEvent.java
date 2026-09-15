package io.github.bearisbug.synaxis.event.item;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

public class EntityEquipEvent implements EntityInstanceEvent, ItemEvent {

    private final Entity entity;
    private ItemStack equippedItem;
    private final EquipmentSlot slot;

    public EntityEquipEvent(Entity entity, ItemStack equippedItem, EquipmentSlot slot) {
        this.entity = entity;
        this.equippedItem = equippedItem;
        this.slot = slot;
    }

    public ItemStack getEquippedItem() {
        return equippedItem;
    }

    public void setEquippedItem(ItemStack armorItem) {
        this.equippedItem = armorItem;
    }

    public EquipmentSlot getSlot() {
        return slot;
    }

    /**
     * Same as {@link #getEquippedItem()}.
     */
    @Override
    public ItemStack getItemStack() {
        return equippedItem;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
