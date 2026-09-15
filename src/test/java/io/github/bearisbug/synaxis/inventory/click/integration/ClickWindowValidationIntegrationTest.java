package io.github.bearisbug.synaxis.inventory.click.integration;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.inventory.InventoryPreClickEvent;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.inventory.click.ClickUtils.clickPacket;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowPacket.ClickType.PICKUP;
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
