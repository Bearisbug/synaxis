package io.github.bearisbug.synaxis.event.item;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.event.inventory.InventoryPreClickEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSwapItemEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * Called whenever an item is written into an equipment slot of a living entity. On a player these are the armor
 * slots, the off hand and the selected hotbar slot, and every write counts: taking an item off writes air, and a picked
 * up item landing in the selected slot is a write too. Selecting another hotbar slot is not. The event cannot be
 * cancelled, and the item it replaces is not given back anywhere.
 * <p>
 * On a player the event is called while the slot is written, inside the player inventory's monitor and, during a
 * click, before the click has been committed: listeners must not open or close inventories. Only a replacement with the
 * material and the amount of the requested item is stored there, so a listener may change components; any other
 * replacement is ignored, the requested item is stored, and the first such refusal is logged. To keep an item from being
 * equipped, cancel {@link InventoryPreClickEvent} or {@link PlayerSwapItemEvent} instead. On other living entities the
 * item set here is stored as it is.
 */
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
