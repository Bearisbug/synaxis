package dev.gameengine.inventory.click.integration;

import net.kyori.adventure.text.Component;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.inventory.Inventory;
import dev.gameengine.inventory.InventoryType;
import dev.gameengine.item.ItemStack;
import dev.gameengine.item.Material;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static dev.gameengine.inventory.click.ClickUtils.clickPacket;
import static dev.gameengine.network.packet.client.play.ClientClickWindowPacket.ClickType.QUICK_MOVE;
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

    /**
     * The hotbar pass only takes one item here, and the remaining 63 have to keep going into the main inventory
     * instead of being written back into the container.
     */
    @Test
    public void shiftClickOutOfContainerFallsBackToTheMainInventory(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        player.openInventory(inventory);

        var playerInventory = player.getInventory();
        playerInventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 63));
        for (int i = 1; i < 9; i++) playerInventory.setItemStack(i, ItemStack.of(Material.STONE, 64));
        inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 64));

        player.addPacketToQueue(clickPacket(QUICK_MOVE, inventory.getWindowId(), 0, 0));
        player.interpretPacketQueue();

        assertEquals(ItemStack.AIR, inventory.getItemStack(0), "the whole stack has to leave the container");
        assertEquals(ItemStack.of(Material.DIAMOND, 64), playerInventory.getItemStack(0));
        assertEquals(ItemStack.of(Material.DIAMOND, 63), playerInventory.getItemStack(35));
    }

    /**
     * Same fallback inside the player inventory: the main inventory pass tops up a partial stack and the rest has
     * to keep going into the hotbar.
     */
    @Test
    public void shiftClickOutOfCraftingGridFallsBackToTheHotbar(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = player.getInventory();

        for (int i = 9; i < 35; i++) inventory.setItemStack(i, ItemStack.of(Material.STONE, 64));
        inventory.setItemStack(35, ItemStack.of(Material.DIAMOND, 63));
        inventory.setItemStack(37, ItemStack.of(Material.DIAMOND, 64)); // First crafting grid slot

        // Minestom slot 37 is window slot 1 of window 0
        player.addPacketToQueue(clickPacket(QUICK_MOVE, 0, 0, 1));
        player.interpretPacketQueue();

        assertEquals(ItemStack.AIR, inventory.getItemStack(37), "the whole stack has to leave the crafting grid");
        assertEquals(ItemStack.of(Material.DIAMOND, 64), inventory.getItemStack(35));
        assertEquals(ItemStack.of(Material.DIAMOND, 63), inventory.getItemStack(0));
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
