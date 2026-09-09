package net.minestom.server.inventory.click.integration;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static net.minestom.server.inventory.click.ClickUtils.clickPacket;
import static net.minestom.server.network.packet.client.play.ClientClickWindowPacket.ClickType.QUICK_MOVE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class ShiftClickIntegrationTest {

    /**
     * Slot 0 of a crafting menu is the result slot, so an item shift-clicked in from the player inventory has to
     * land in the grid instead.
     */
    @Test
    public void shiftClickIntoCraftingMustSkipResultSlot(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CRAFTING, Component.empty()); // size 10, slot 0 is the result
        player.openInventory(inventory);
        player.getInventory().setItemStack(0, ItemStack.of(Material.STICK, 64));

        // In a size 10 window, hotbar slot 0 is window slot 37
        player.addPacketToQueue(clickPacket(QUICK_MOVE, inventory.getWindowId(), 0, 37));
        player.interpretPacketQueue();

        assertEquals(ItemStack.AIR, inventory.getItemStack(0), "the result slot must not accept input");
        assertEquals(ItemStack.of(Material.STICK, 64), inventory.getItemStack(1));
        assertEquals(ItemStack.AIR, player.getInventory().getItemStack(0));
    }

    /**
     * Shift-clicking into a furnace must not drop the item into the output slot either.
     */
    @Test
    public void shiftClickIntoFurnaceMustSkipOutputSlot(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.FURNACE, Component.empty()); // size 3, slot 2 is the output
        player.openInventory(inventory);
        inventory.setItemStack(0, ItemStack.of(Material.STONE, 64));
        inventory.setItemStack(1, ItemStack.of(Material.STONE, 64));
        player.getInventory().setItemStack(0, ItemStack.of(Material.STICK, 64));

        // In a size 3 window, hotbar slot 0 is window slot 30
        player.addPacketToQueue(clickPacket(QUICK_MOVE, inventory.getWindowId(), 0, 30));
        player.interpretPacketQueue();

        assertEquals(ItemStack.AIR, inventory.getItemStack(2), "the output slot must not accept input");
        assertEquals(ItemStack.of(Material.STICK, 64), player.getInventory().getItemStack(0),
                "the item stays put when no input slot is free");
    }

    @Test
    public void shiftClickIntoChestFillsTheFirstFreeSlot(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty()); // size 9
        player.openInventory(inventory);
        player.getInventory().setItemStack(0, ItemStack.of(Material.STICK, 64));

        // In a size 9 window, hotbar slot 0 is window slot 36
        player.addPacketToQueue(clickPacket(QUICK_MOVE, inventory.getWindowId(), 0, 36));
        player.interpretPacketQueue();

        assertEquals(ItemStack.of(Material.STICK, 64), inventory.getItemStack(0));
        assertEquals(ItemStack.AIR, player.getInventory().getItemStack(0));
    }
}
