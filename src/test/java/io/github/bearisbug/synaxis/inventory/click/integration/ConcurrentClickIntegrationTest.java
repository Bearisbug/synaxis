package io.github.bearisbug.synaxis.inventory.click.integration;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
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

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ConcurrentClickIntegrationTest {

    private static final int ROUNDS = 300;

    /**
     * Two players clicking the same shared inventory read the clicked slot, compute a result and write it back.
     * Without a lock spanning the whole transaction both of them pick up the same stack.
     */
    @Test
    public void concurrentLeftClickMustNotDuplicate(Env env) throws Exception {
        var instance = env.createFlatInstance();
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        var first = env.createPlayer(instance, new Pos(0, 42, 0));
        var second = env.createPlayer(instance, new Pos(0, 42, 0));
        first.openInventory(inventory);
        second.openInventory(inventory);

        var barrier = new CyclicBarrier(2);
        var failure = new AtomicReference<Throwable>();

        for (int round = 0; round < ROUNDS; round++) {
            inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 64));
            first.getInventory().setCursorItem(ItemStack.AIR);
            second.getInventory().setCursorItem(ItemStack.AIR);

            var firstThread = click(inventory, first, barrier, failure);
            var secondThread = click(inventory, second, barrier, failure);
            firstThread.start();
            secondThread.start();
            firstThread.join();
            secondThread.join();

            assertNull(failure.get(), "click threw");
            final int total = amount(inventory.getItemStack(0))
                    + amount(first.getInventory().getCursorItem())
                    + amount(second.getInventory().getCursorItem());
            assertEquals(64, total, "items were duplicated or lost in round " + round);
        }
    }

    /**
     * A click on a container reads and writes the clicking player's inventory as well, so a write coming from
     * another thread must not be able to interleave between the read and the write of the click.
     */
    @Test
    public void concurrentInventoryWriteDuringContainerClickMustNotBeLost(Env env) throws Exception {
        var instance = env.createFlatInstance();
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        player.openInventory(inventory);

        // The click drops one diamond of the half stack in slot 9 and writes back what it read minus one, the
        // concurrent insertion tops up that same stack
        var playerInventory = player.getInventory();
        playerInventory.setItemStack(9, ItemStack.of(Material.DIAMOND, 32));

        var added = new AtomicBoolean();
        var insertion = new Thread(() -> added.set(playerInventory.addItemStack(ItemStack.of(Material.DIAMOND, 1))));

        // Run the concurrent insertion between the read and the write of the click. The drop event is dispatched
        // while the click runs, since its result decides the click; the click events wait until it is committed
        var released = new AtomicBoolean();
        env.process().eventHandler().addListener(ItemDropEvent.class, _ -> {
            if (!released.compareAndSet(false, true)) return;
            insertion.start();
            try {
                insertion.join(500);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });

        // Slot 9 of the player inventory comes after the slots of the container
        inventory.handleClick(player, new Click.DropSlot(inventory.getSize() + 9, false));
        insertion.join();

        assertTrue(released.get(), "the click never dropped an item");
        assertTrue(added.get(), "the concurrent insertion did not fit");
        final int dropped = 1;
        final int total = diamonds(inventory) + diamonds(playerInventory) + diamonds(playerInventory.getCursorItem()) + dropped;
        assertEquals(33, total, "the concurrent insertion was overwritten by the click");
    }

    private static int diamonds(AbstractInventory inventory) {
        int total = 0;
        for (ItemStack itemStack : inventory.getItemStacks()) total += diamonds(itemStack);
        return total;
    }

    private static int diamonds(ItemStack itemStack) {
        return itemStack.material() == Material.DIAMOND ? itemStack.amount() : 0;
    }

    private static Thread click(Inventory inventory, Player player, CyclicBarrier barrier, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                barrier.await();
                inventory.handleClick(player, new Click.Left(0));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }

    private static int amount(ItemStack itemStack) {
        return itemStack.isAir() ? 0 : itemStack.amount();
    }
}
