package io.github.bearisbug.synaxis.inventory.click.integration;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
