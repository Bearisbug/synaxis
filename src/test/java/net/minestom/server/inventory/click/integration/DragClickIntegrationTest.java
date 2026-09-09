package net.minestom.server.inventory.click.integration;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientCloseWindowPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.minestom.server.inventory.click.ClickUtils.clickPacket;
import static net.minestom.server.network.packet.client.play.ClientClickWindowPacket.ClickType.QUICK_CRAFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnvTest
public class DragClickIntegrationTest {

    /**
     * A drag referencing a slot outside of the target inventories must reject the whole transaction instead of
     * writing the first slots and then throwing, which duplicates the cursor item.
     */
    @Test
    public void outOfBoundsDragIsRejectedEntirely(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_3_ROW, Component.empty()); // 27
        player.openInventory(inventory);
        player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND, 64));

        // 89 - 27 = 62, which is past PlayerInventory#INVENTORY_SIZE (46)
        assertFalse(inventory.dragging(player, List.of(0, 89), 2));

        assertEquals(ItemStack.AIR, inventory.getItemStack(0), "no slot may be written by a rejected drag");
        assertEquals(ItemStack.of(Material.DIAMOND, 64), player.getInventory().getCursorItem(),
                "the cursor must be left untouched by a rejected drag");
    }

    /**
     * The drag cache is keyed to the inventory that was open when the slots were recorded. Reopening a smaller
     * inventory must not let those stale slots address memory past the new inventory.
     */
    @Test
    public void staleDragSlotsMustNotDuplicateItems(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));

        var big = new Inventory(InventoryType.CHEST_6_ROW, Component.empty()); // 54
        player.openInventory(big);

        // Start a left drag over window slot 0 (minestom 0) and window slot 80 (minestom 89)
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, big.getWindowId(), 0, -999));
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, big.getWindowId(), 1, 0));
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, big.getWindowId(), 1, 80));
        player.interpretPacketQueue();

        // The client claims it closed window 0 while a container is open
        player.addPacketToQueue(new ClientCloseWindowPacket(0));
        player.interpretPacketQueue();

        var small = new Inventory(InventoryType.CHEST_3_ROW, Component.empty()); // 27
        player.openInventory(small);
        player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND, 64));

        player.addPacketToQueue(clickPacket(QUICK_CRAFT, small.getWindowId(), 2, -999));
        player.interpretPacketQueue();

        assertEquals(ItemStack.AIR, small.getItemStack(0), "stale drag slots must not be applied");
        assertEquals(ItemStack.of(Material.DIAMOND, 64), player.getInventory().getCursorItem(),
                "the cursor must not keep its items after a partially applied drag");
    }

    @Test
    public void leftDragSpreadsCursorEvenly(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty()); // 9
        player.openInventory(inventory);
        player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND, 6));

        drag(player, inventory, 0, 1, 2);

        assertEquals(ItemStack.of(Material.DIAMOND, 2), inventory.getItemStack(0));
        assertEquals(ItemStack.of(Material.DIAMOND, 2), inventory.getItemStack(1));
        assertEquals(ItemStack.of(Material.DIAMOND, 2), inventory.getItemStack(2));
        assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
    }

    @Test
    public void rightDragPlacesOneItemPerSlot(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty()); // 9
        player.openInventory(inventory);
        player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND, 6));

        // Right drag: button 4 starts, 5 adds a slot, 6 ends
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, inventory.getWindowId(), 4, -999));
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, inventory.getWindowId(), 5, 0));
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, inventory.getWindowId(), 5, 1));
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, inventory.getWindowId(), 6, -999));
        player.interpretPacketQueue();

        assertEquals(ItemStack.of(Material.DIAMOND, 1), inventory.getItemStack(0));
        assertEquals(ItemStack.of(Material.DIAMOND, 1), inventory.getItemStack(1));
        assertEquals(ItemStack.of(Material.DIAMOND, 4), player.getInventory().getCursorItem());
    }

    /**
     * A drag that spans the container and the player inventory must reach both.
     */
    @Test
    public void leftDragReachesThePlayerInventory(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty()); // 9
        player.openInventory(inventory);
        player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND, 4));

        // Window slot 9 is the first player inventory slot, which maps to minestom slot 9 + 9 = 18
        drag(player, inventory, 0, 9);

        assertEquals(ItemStack.of(Material.DIAMOND, 2), inventory.getItemStack(0));
        assertEquals(ItemStack.of(Material.DIAMOND, 2), player.getInventory().getItemStack(9));
        assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
    }

    private static void drag(Player player, Inventory inventory, int... windowSlots) {
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, inventory.getWindowId(), 0, -999));
        for (int slot : windowSlots) {
            player.addPacketToQueue(clickPacket(QUICK_CRAFT, inventory.getWindowId(), 1, slot));
        }
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, inventory.getWindowId(), 2, -999));
        player.interpretPacketQueue();
    }
}
