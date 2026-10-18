package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins what the transaction options return and that a list of items is not one transaction, as their javadoc states.
 */
@RegistriesTest
public class InventoryTransactionRegistriesTest {

    @Test
    public void takeAllReturnsWhatWasNotFound() {
        Inventory inventory = new Inventory(InventoryType.CHEST_1_ROW, "title");
        inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 40));

        assertEquals(ItemStack.of(Material.DIAMOND, 24), inventory.takeItemStack(ItemStack.of(Material.DIAMOND, 64), TransactionOption.ALL));
        assertEquals(ItemStack.AIR, inventory.getItemStack(0));
    }

    @Test
    public void aListOfItemsIsNotOneTransaction() {
        Inventory inventory = new Inventory(InventoryType.HOPPER, "title"); // 5 slots
        for (int slot = 0; slot < 4; slot++) inventory.setItemStack(slot, ItemStack.of(Material.STONE, 64));

        // Each item is checked against the unchanged inventory: both fit the last free slot on their own
        assertEquals(List.of(true, true), inventory.addItemStacks(
                List.of(ItemStack.of(Material.GOLD_INGOT, 64), ItemStack.of(Material.GOLD_INGOT, 64)), TransactionOption.DRY_RUN));
        assertEquals(ItemStack.AIR, inventory.getItemStack(4));

        // All or nothing holds for each item: the first one stays added when the second one is refused
        assertEquals(List.of(true, false), inventory.addItemStacks(
                List.of(ItemStack.of(Material.DIAMOND), ItemStack.of(Material.GOLD_INGOT, 64)), TransactionOption.ALL_OR_NOTHING));
        assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(4));
    }
}
