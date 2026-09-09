package net.minestom.server.inventory.click.integration;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static net.minestom.server.inventory.click.ClickUtils.clickPacket;
import static net.minestom.server.network.packet.client.play.ClientClickWindowPacket.ClickType.PICKUP;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class ClickWindowValidationIntegrationTest {

    /**
     * A click still carrying the window id of a previously opened inventory must not be applied to the inventory
     * that is open now.
     */
    @Test
    public void clickWithStaleWindowIdMustBeIgnored(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));

        var first = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        var second = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        second.setItemStack(0, ItemStack.of(Material.DIAMOND, 64));

        player.openInventory(second);

        player.addPacketToQueue(clickPacket(PICKUP, first.getWindowId(), 0, 0));
        player.interpretPacketQueue();

        assertEquals(ItemStack.of(Material.DIAMOND, 64), second.getItemStack(0),
                "a click for another window must not touch the open inventory");
        assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
    }

    /**
     * Closing the inventory from an {@link InventoryPreClickEvent} listener has to cancel the click, which is the
     * documented way for a GUI to make a menu entry a single-shot action.
     */
    @Test
    public void closingInventoryInPreClickCancelsTheClick(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));

        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 64));
        player.openInventory(inventory);

        env.process().eventHandler().addListener(InventoryPreClickEvent.class, _ -> player.closeInventory());

        player.addPacketToQueue(clickPacket(PICKUP, inventory.getWindowId(), 0, 0));
        player.interpretPacketQueue();

        assertEquals(ItemStack.AIR, player.getInventory().getCursorItem(), "the click must not be applied");
        assertEquals(ItemStack.of(Material.DIAMOND, 64), inventory.getItemStack(0));
    }
}
