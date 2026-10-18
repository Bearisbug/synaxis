package io.github.bearisbug.synaxis.inventory;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EquipmentSlot;
import io.github.bearisbug.synaxis.event.inventory.InventoryItemChangeEvent;
import io.github.bearisbug.synaxis.event.item.EntityEquipEvent;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.HELMET_SLOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A click computes the cursor before it writes the slot, so on a player inventory an {@link EntityEquipEvent} listener
 * may only change the components of the equipped item: another material or amount would duplicate or destroy items.
 */
@EnvTest
public class EntityEquipEventIntegrationTest {

    private static final ItemStack HELMET = ItemStack.of(Material.DIAMOND_HELMET);

    @Test
    public void puttingTheHelmetBackWhenItIsTakenOffDoesNotDuplicateIt(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = player.getInventory();
        inventory.setItemStack(HELMET_SLOT, HELMET);

        // "This helmet cannot be taken off"
        env.process().eventHandler().addListener(EntityEquipEvent.class, event -> {
            if (event.getSlot() == EquipmentSlot.HELMET && event.getEquippedItem().isAir()) event.setEquippedItem(HELMET);
        });

        assertTrue(inventory.handleClick(player, new Click.Left(HELMET_SLOT)));

        assertEquals(1, helmets(inventory) + helmets(inventory.getCursorItem()), "helmets in the slots and on the cursor");
        assertEquals(ItemStack.AIR, inventory.getItemStack(HELMET_SLOT));
        assertEquals(HELMET, inventory.getCursorItem());
        assertEquals(ItemStack.AIR, player.getEquipment(EquipmentSlot.HELMET));
    }

    @Test
    public void refusingTheHelmetWhenItIsPutOnDoesNotDestroyIt(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = player.getInventory();
        inventory.setCursorItem(HELMET);

        // "Your level is too low to wear this"
        env.process().eventHandler().addListener(EntityEquipEvent.class, event -> {
            if (event.getSlot() == EquipmentSlot.HELMET && !event.getEquippedItem().isAir()) event.setEquippedItem(ItemStack.AIR);
        });

        assertTrue(inventory.handleClick(player, new Click.Left(HELMET_SLOT)));

        assertEquals(1, helmets(inventory) + helmets(inventory.getCursorItem()), "helmets in the slots and on the cursor");
        assertEquals(HELMET, inventory.getItemStack(HELMET_SLOT));
        assertEquals(ItemStack.AIR, inventory.getCursorItem());
    }

    @Test
    public void aListenerMayChangeTheComponentsAndTheChangeEventReportsWhatWasStored(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = player.getInventory();
        var marked = HELMET.with(DataComponents.LORE, List.of(Component.text("equipped")));

        env.process().eventHandler().addListener(EntityEquipEvent.class, event -> {
            if (event.getEquippedItem().material() == Material.DIAMOND_HELMET) event.setEquippedItem(marked);
        });
        var changed = new AtomicReference<InventoryItemChangeEvent>();
        inventory.eventNode().addListener(InventoryItemChangeEvent.class, changed::set);

        inventory.setItemStack(HELMET_SLOT, HELMET);

        assertEquals(marked, inventory.getItemStack(HELMET_SLOT));
        assertEquals(marked, player.getEquipment(EquipmentSlot.HELMET));
        assertNotNull(changed.get(), "no InventoryItemChangeEvent");
        assertEquals(marked, changed.get().getNewItem(), "the new item of the change event");
    }

    private static int helmets(AbstractInventory inventory) {
        int total = 0;
        for (ItemStack itemStack : inventory.getItemStacks()) total += helmets(itemStack);
        return total;
    }

    private static int helmets(ItemStack itemStack) {
        return itemStack.material() == Material.DIAMOND_HELMET ? itemStack.amount() : 0;
    }
}
