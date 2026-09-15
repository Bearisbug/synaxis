package io.github.bearisbug.synaxis.inventory.click.integration;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.inventory.click.ClickUtils.clickPacket;
import static io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowPacket.ClickType.PICKUP_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class DoubleClickIntegrationTest {

    /**
     * Vanilla collects with two passes: the first one may only draw from stacks that are not already full. Doing a
     * single pass empties the first full stack it walks into and leaves the scattered items alone.
     */
    @Test
    public void doubleClickPrefersPartialStacks(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = player.getInventory();

        inventory.setItemStack(1, ItemStack.of(Material.COBBLESTONE, 64)); // Full stack in the hotbar
        for (int i = 9; i < 36; i++) inventory.setItemStack(i, ItemStack.of(Material.COBBLESTONE, 1));
        inventory.setCursorItem(ItemStack.of(Material.COBBLESTONE, 1));

        // Window slot 36 is hotbar slot 0, which is empty
        player.addPacketToQueue(clickPacket(PICKUP_ALL, 0, 0, 36));
        player.interpretPacketQueue();

        assertEquals(64, inventory.getCursorItem().amount());
        for (int i = 9; i < 36; i++) {
            assertEquals(ItemStack.AIR, inventory.getItemStack(i), "partial stacks must be collected first: slot " + i);
        }
        assertEquals(28, inventory.getItemStack(1).amount(), "the full stack may only top up the remainder");
    }

    @Test
    public void doubleClickCollectsFromTheOpenInventory(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        player.openInventory(inventory);

        inventory.setItemStack(1, ItemStack.of(Material.COBBLESTONE, 10));
        inventory.setItemStack(2, ItemStack.of(Material.COBBLESTONE, 10));
        player.getInventory().setCursorItem(ItemStack.of(Material.COBBLESTONE, 1));

        player.addPacketToQueue(clickPacket(PICKUP_ALL, inventory.getWindowId(), 0, 0));
        player.interpretPacketQueue();

        assertEquals(21, player.getInventory().getCursorItem().amount());
        assertEquals(ItemStack.AIR, inventory.getItemStack(1));
        assertEquals(ItemStack.AIR, inventory.getItemStack(2));
    }
}
