package dev.gameengine.event.item;

import dev.gameengine.entity.Entity;
import dev.gameengine.entity.EquipmentSlot;
import dev.gameengine.event.trait.EntityInstanceEvent;
import dev.gameengine.event.trait.ItemEvent;
import dev.gameengine.item.ItemStack;

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
