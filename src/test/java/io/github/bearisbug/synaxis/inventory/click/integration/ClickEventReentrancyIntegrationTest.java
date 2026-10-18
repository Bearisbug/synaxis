package io.github.bearisbug.synaxis.inventory.click.integration;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.inventory.InventoryClickEvent;
import io.github.bearisbug.synaxis.event.inventory.InventoryItemChangeEvent;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A click writes its slots before the cursor. A listener of the events it raises that closes the inventory takes the
 * cursor off: it has to find the committed cursor, otherwise the items the click has just placed are dropped or stored
 * a second time.
 */
@EnvTest
public class ClickEventReentrancyIntegrationTest {

    @Test
    public void closingTheInventoryFromAClickEventOfADragDuplicatesNothing(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = player.getInventory();
        inventory.setCursorItem(ItemStack.of(Material.STONE, 32));

        var dropped = countDrops(env);
        var closed = new AtomicBoolean();
        var heldMonitor = new AtomicBoolean();
        env.process().eventHandler().addListener(InventoryClickEvent.class, _ -> {
            if (Thread.holdsLock(inventory)) heldMonitor.set(true);
            if (closed.compareAndSet(false, true)) player.closeInventory();
        });

        assertTrue(inventory.handleClick(player, new Click.LeftDrag(List.of(0, 1))));

        assertTrue(closed.get(), "no InventoryClickEvent was dispatched");
        assertEquals(32, stone(inventory) + inventory.getCursorItem().amount() + dropped.get(),
                "slots + cursor + dropped after the drag");
        assertEquals(ItemStack.of(Material.STONE, 16), inventory.getItemStack(0));
        assertEquals(ItemStack.of(Material.STONE, 16), inventory.getItemStack(1));
        assertFalse(heldMonitor.get(), "the click event is dispatched while the inventory monitor is held");
    }

    @Test
    public void closingTheContainerFromAnItemChangeEventStoresNothingTwice(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var container = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        player.openInventory(container);
        var playerInventory = player.getInventory();
        playerInventory.setCursorItem(ItemStack.of(Material.DIAMOND, 64));

        // A cancelled drop puts the cursor back into the player inventory
        env.process().eventHandler().addListener(ItemDropEvent.class, event -> event.setCancelled(true));
        container.eventNode().addListener(InventoryItemChangeEvent.class, _ -> player.closeInventory());

        assertTrue(container.handleClick(player, new Click.Left(0)));

        assertEquals(ItemStack.of(Material.DIAMOND, 64), container.getItemStack(0));
        assertEquals(64, diamonds(container) + diamonds(playerInventory) + playerInventory.getCursorItem().amount(),
                "container + player inventory + cursor after the click");
    }

    @Test
    public void closingTheInventoryFromAClickEventOfADoubleClickDuplicatesNothing(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = player.getInventory();
        inventory.setItemStack(9, ItemStack.of(Material.STONE, 10));
        inventory.setItemStack(10, ItemStack.of(Material.STONE, 10));
        inventory.setCursorItem(ItemStack.of(Material.STONE, 1));

        var dropped = countDrops(env);
        var closed = new AtomicBoolean();
        env.process().eventHandler().addListener(InventoryClickEvent.class, _ -> {
            if (closed.compareAndSet(false, true)) player.closeInventory();
        });

        assertTrue(inventory.handleClick(player, new Click.Double(0)));

        assertTrue(closed.get(), "no InventoryClickEvent was dispatched");
        assertEquals(21, stone(inventory) + inventory.getCursorItem().amount() + dropped.get(),
                "slots + cursor + dropped after the double click");
    }

    private static AtomicInteger countDrops(Env env) {
        var dropped = new AtomicInteger();
        env.process().eventHandler().addListener(ItemDropEvent.class, event -> dropped.addAndGet(event.getItemStack().amount()));
        return dropped;
    }

    private static int stone(AbstractInventory inventory) {
        return count(inventory, Material.STONE);
    }

    private static int diamonds(AbstractInventory inventory) {
        return count(inventory, Material.DIAMOND);
    }

    private static int count(AbstractInventory inventory, Material material) {
        int total = 0;
        for (ItemStack itemStack : inventory.getItemStacks()) {
            if (itemStack.material() == material) total += itemStack.amount();
        }
        return total;
    }
}
