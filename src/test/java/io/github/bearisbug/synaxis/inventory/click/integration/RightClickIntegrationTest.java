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
public class RightClickIntegrationTest {

    @Test
    public void rightSelf(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        var inventory = player.getInventory();
        var listener = env.listen(InventoryPreClickEvent.class);
        inventory.setItemStack(1, ItemStack.of(Material.DIAMOND));
        inventory.setItemStack(2, ItemStack.of(Material.DIAMOND));
        // Empty click
        {
            listener.followup(event -> {
                assertEquals(event.getInventory(), inventory);
                assertEquals(new Click.Right(0), event.getClick());
                assertEquals(ItemStack.AIR, inventory.getCursorItem());
            });
            rightClick(player, 0);
        }
        // Pickup diamond
        {
            listener.followup(event -> {
                assertEquals(new Click.Right(1), event.getClick());
                assertEquals(ItemStack.AIR, inventory.getCursorItem());
                assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(1));
            });
            rightClick(player, 1);
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(1));
        }
        // Place it back
        {
            listener.followup(event -> {
                assertEquals(new Click.Right(1), event.getClick());
                assertEquals(ItemStack.of(Material.DIAMOND), inventory.getCursorItem());
                assertEquals(ItemStack.AIR, inventory.getItemStack(1));
            });
            rightClick(player, 1);
            assertEquals(ItemStack.AIR, inventory.getCursorItem());
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(1));
        }
        // Pickup diamond
        {
            listener.followup(event -> {
                assertEquals(new Click.Right(1), event.getClick());
                assertEquals(ItemStack.AIR, inventory.getCursorItem());
                assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(1));
            });
            rightClick(player, 1);
            assertEquals(ItemStack.of(Material.DIAMOND), inventory.getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(1));
        }
        // Stack diamond
        {
            listener.followup(event -> {
                assertEquals(new Click.Right(2), event.getClick());
                assertEquals(ItemStack.of(Material.DIAMOND), inventory.getCursorItem());
                assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(2));
            });
            rightClick(player, 2);
            assertEquals(ItemStack.AIR, inventory.getCursorItem());
            assertEquals(ItemStack.of(Material.DIAMOND, 2), inventory.getItemStack(2));
        }
        // Cancel event
        {
            listener.followup(event -> event.setCancelled(true));
            rightClick(player, 2);
            assertEquals(ItemStack.AIR, inventory.getCursorItem(), "Left click cancellation did not work");
            assertEquals(ItemStack.of(Material.DIAMOND, 2), inventory.getItemStack(2));
        }
        // Change items
        {
            listener.followup(event -> {
                Click.Right right = assertInstanceOf(Click.Right.class, event.getClick());

                inventory.setItemStack(right.slot(), ItemStack.of(Material.DIAMOND, 5));
                inventory.setCursorItem(ItemStack.of(Material.DIAMOND));
            });
            rightClick(player, 1);
            assertEquals(ItemStack.AIR, inventory.getCursorItem());
            assertEquals(ItemStack.of(Material.DIAMOND, 6), inventory.getItemStack(1));
        }
    }

    @Test
    public void rightExternal(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        var inventory = new Inventory(InventoryType.HOPPER, "test");
        player.openInventory(inventory);
        var listener = env.listen(InventoryPreClickEvent.class);
        inventory.setItemStack(1, ItemStack.of(Material.DIAMOND));
        // Empty click in player inv
        {
            listener.followup(event -> {
                assertEquals(player.getInventory(), event.getInventory());
                assertEquals(new Click.Right(0), event.getClick());
                assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            });
            rightClick(player, 0);
        }
        // Pickup diamond
        {
            listener.followup(event -> {
                assertEquals(inventory, event.getInventory());
                assertEquals(new Click.Right(1), event.getClick());
                assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
                assertEquals(ItemStack.of(Material.DIAMOND), inventory.getItemStack(1));
            });
            rightClickOpenInventory(player, 1);
            assertEquals(ItemStack.of(Material.DIAMOND), player.getInventory().getCursorItem());
            assertEquals(ItemStack.AIR, inventory.getItemStack(1));
        }
        // Place back to player inv
        {
            listener.followup(event -> {
                assertEquals(player.getInventory(), event.getInventory());
                assertEquals(new Click.Right(1), event.getClick());
                assertEquals(ItemStack.of(Material.DIAMOND), player.getInventory().getCursorItem());
                assertEquals(ItemStack.AIR, inventory.getItemStack(1));
                assertEquals(ItemStack.AIR, player.getInventory().getItemStack(1));
            });
            rightClick(player, 1);
            assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            assertEquals(ItemStack.of(Material.DIAMOND), player.getInventory().getItemStack(1));
        }
        // Cancel event
        {
            listener.followup(event -> event.setCancelled(true));
            rightClick(player, 1);
            assertEquals(ItemStack.of(Material.DIAMOND), player.getInventory().getItemStack(1), "Left click cancellation did not work");
            assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
        }
        // Change items
        {
            listener.followup(event -> {
                assertEquals(player.getInventory(), event.getInventory());
                assertEquals(new Click.Right(9), event.getClick());

                Click.Right right = assertInstanceOf(Click.Right.class, event.getClick());

                event.getInventory().setItemStack(right.slot(), ItemStack.of(Material.DIAMOND, 5));
                player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND));
            });
            rightClick(player, 9);
            assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
            assertEquals(ItemStack.of(Material.DIAMOND, 6), player.getInventory().getItemStack(9));
        }
    }

    private static void rightClickOpenInventory(Player player, int slot) {
        _rightClick(player.getOpenInventory(), true, player, slot);
    }

    private static void rightClick(Player player, int slot) {
        _rightClick(player.getOpenInventory(), false, player, slot);
    }

    private static void _rightClick(AbstractInventory openInventory, boolean clickOpenInventory, Player player, int slot) {
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
        player.addPacketToQueue(new ClientClickWindowPacket(windowId, 0, (short) slot, (byte) 1,
                ClientClickWindowPacket.ClickType.PICKUP, Map.of(), ItemStack.Hash.AIR));
        player.interpretPacketQueue();
    }
}
