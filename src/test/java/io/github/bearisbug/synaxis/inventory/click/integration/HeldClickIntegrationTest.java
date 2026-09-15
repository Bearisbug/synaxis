package io.github.bearisbug.synaxis.inventory.click.integration;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.inventory.InventoryPreClickEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowPacket;
import io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnvTest
public class HeldClickIntegrationTest {

    @Test
    public void heldSelf(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        var inventory = player.getInventory();
        var listener = env.listen(InventoryPreClickEvent.class);
        inventory.setItemStack(1, ItemStack.of(Material.DIAMOND));
        inventory.setItemStack(2, ItemStack.of(Material.GOLD_INGOT));
        inventory.setItemStack(3, ItemStack.of(Material.EGG));
        inventory.setItemStack(6, ItemStack.of(Material.DIAMOND));
        // Empty
        {
            listener.followup(event -> {
                assertEquals(event.getInventory(), inventory);
                assertEquals(new Click.HotbarSwap(5, 4), event.getClick());

                Click.HotbarSwap swap = assertInstanceOf(Click.HotbarSwap.class, event.getClick());
                assertEquals(ItemStack.AIR, inventory.getCursorItem());
                assertEquals(ItemStack.AIR, inventory.getItemStack(swap.slot()));
            });
            heldClick(player, 4, 5);
        }
        // Swap air
        {
            listener.followup(event -> {
                assertEquals(event.getInventory(), inventory);
                assertEquals(new Click.HotbarSwap(0, 1), event.getClick());

                Click.HotbarSwap swap = assertInstanceOf(Click.HotbarSwap.class, event.getClick());
                assertEquals(ItemStack.AIR, inventory.getCursorItem());
                assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(swap.slot()));
            });
            heldClick(player, 1, 0);
            assertEquals(ItemStack.AIR, inventory.getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(1));
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(0));
        }
        // Swap items
        {
            listener.followup(event -> {
                assertEquals(new Click.HotbarSwap(2, 0), event.getClick());
                assertEquals(ItemStack.AIR, inventory.getCursorItem());
                assertEquals(ItemStack.AIR, inventory.getItemStack(1));
            });
            heldClick(player, 0, 2);
            assertEquals(ItemStack.AIR, inventory.getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(1));
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(2));
            assertEquals(ItemStack.of(Material.GOLD_INGOT), inventory.getItemStack(0));
        }
        // Swap offhand
        {
            listener.followup(event -> assertEquals(new Click.OffhandSwap(3), event.getClick()));
            heldClick(player, 3, 40);
            assertEquals(ItemStack.AIR, inventory.getItemStack(3));
            assertEquals(ItemStack.of(Material.EGG), player.getItemInOffHand());
        }
        // Cancel event
        {
            listener.followup(event -> event.setCancelled(true));
            heldClick(player, 2, 0);
            assertEquals(ItemStack.AIR, inventory.getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(1));
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(2));
            assertEquals(ItemStack.of(Material.GOLD_INGOT), inventory.getItemStack(0));
        }
    }

    @Test
    public void heldExternal(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        var inventory = new Inventory(InventoryType.HOPPER, "test");
        var playerInv = player.getInventory();
        player.openInventory(inventory);
        var listener = env.listen(InventoryPreClickEvent.class);
        inventory.setItemStack(1, ItemStack.of(Material.DIAMOND));
        inventory.setItemStack(2, ItemStack.of(Material.GOLD_INGOT));
        inventory.setItemStack(3, ItemStack.of(Material.EGG));
        inventory.setItemStack(4, ItemStack.of(Material.DIAMOND));
        // Empty
        {
            listener.followup(event -> {
                assertEquals(inventory, event.getInventory());
                assertEquals(new Click.HotbarSwap(0, 0), event.getClick());
                assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            });
            heldClickOpenInventory(player, 0, 0);
            assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(0));
        }
        // Swap empty
        {
            listener.followup(event -> {
                assertEquals(inventory, event.getInventory());
                assertEquals(new Click.HotbarSwap(0, 1), event.getClick());
                assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            });
            heldClickOpenInventory(player, 1, 0);
            assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(1));
            assertEquals(ItemStack.of(Material.DIAMOND), playerInv.getItemStack(0));
        }
        // Swap items
        {
            listener.followup(event -> {
                assertEquals(inventory, event.getInventory());
                assertEquals(new Click.HotbarSwap(0, 2), event.getClick());
                assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            });
            heldClickOpenInventory(player, 2, 0);
            assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(2));
            assertEquals(ItemStack.of(Material.GOLD_INGOT), playerInv.getItemStack(0));
        }
        // Swap offhand
        {
            listener.followup(event -> {
                assertEquals(inventory, event.getInventory());
                assertEquals(new Click.OffhandSwap(3), event.getClick());
            });
            heldClickOpenInventory(player, 3, 40);
            assertEquals(ItemStack.AIR, inventory.getItemStack(3));
            assertEquals(ItemStack.of(Material.EGG), player.getItemInOffHand());
        }
        // Cancel event
        {
            listener.followup(event -> event.setCancelled(true));
            heldClickOpenInventory(player, 2, 0);
            assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(2));
            assertEquals(ItemStack.of(Material.GOLD_INGOT), playerInv.getItemStack(0));
        }
    }

    private static void heldClickOpenInventory(Player player, int slot, int target) {
        _heldClick(player.getOpenInventory(), true, player, slot, target);
    }

    private static void heldClick(Player player, int slot, int target) {
        _heldClick(player.getOpenInventory(), false, player, slot, target);
    }

    private static void _heldClick(AbstractInventory openInventory, boolean clickOpenInventory, Player player, int slot, int target) {
        final byte windowId = openInventory != null ? openInventory.getWindowId() : 0;
        if (clickOpenInventory) {
            assertNotNull(openInventory);
            // Do not touch slot
        } else {
            int offset = openInventory != null ? openInventory.getInnerSize() : 0;
            slot = PlayerInventoryUtils.convertEngineSlotToWindowSlot(slot);
            if (openInventory != null) {
                slot = slot - 9 + offset;
            }
        }
        player.addPacketToQueue(new ClientClickWindowPacket(windowId, 0, (short) slot, (byte) target,
                ClientClickWindowPacket.ClickType.SWAP, Map.of(), ItemStack.Hash.AIR));
        player.interpretPacketQueue();
    }
}
