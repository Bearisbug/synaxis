package io.github.bearisbug.synaxis.inventory;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class CursorItemOnCloseIntegrationTest {

    @Test
    public void cursorItemGoesBackIntoTheInventoryOnClose(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        player.openInventory(inventory);

        env.process().eventHandler().addListener(ItemDropEvent.class, event -> event.setCancelled(true));
        player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND, 5));

        player.closeInventory();

        assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
        assertEquals(ItemStack.of(Material.DIAMOND, 5), player.getInventory().getItemStack(0));
    }

    /**
     * When the item can neither be dropped nor stored it has to stay on the cursor rather than vanish.
     */
    @Test
    public void cursorItemIsNotDestroyedWhenItCannotBeStored(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        player.openInventory(inventory);

        final PlayerInventory playerInventory = player.getInventory();
        for (int i = 0; i < playerInventory.getInnerSize(); i++) {
            playerInventory.setItemStack(i, ItemStack.of(Material.STONE, 64));
        }
        env.process().eventHandler().addListener(ItemDropEvent.class, event -> event.setCancelled(true));
        playerInventory.setCursorItem(ItemStack.of(Material.DIAMOND, 5));

        player.closeInventory();

        assertEquals(ItemStack.of(Material.DIAMOND, 5), playerInventory.getCursorItem());
    }
}
