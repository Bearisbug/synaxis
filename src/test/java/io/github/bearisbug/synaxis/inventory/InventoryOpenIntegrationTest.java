package io.github.bearisbug.synaxis.inventory;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.inventory.InventoryOpenEvent;
import io.github.bearisbug.synaxis.inventory.type.VillagerInventory;
import io.github.bearisbug.synaxis.network.packet.server.play.OpenWindowPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TradeListPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class InventoryOpenIntegrationTest {

    /**
     * Adding a viewer shows the window to the client, but only {@link io.github.bearisbug.synaxis.entity.Player#openInventory(Inventory)}
     * makes it the open inventory the clicks, the close and the disconnect are resolved against.
     */
    @Test
    public void addingAViewerWithoutOpeningTheInventoryIsRefused(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(InventoryType.CHEST_1_ROW, "title");
        var opened = connection.trackIncoming(OpenWindowPacket.class);

        assertThrows(IllegalStateException.class, () -> inventory.addViewer(player));

        assertTrue(inventory.getViewers().isEmpty(), "viewers of the inventory");
        opened.assertEmpty();
        assertNull(player.getOpenInventory());

        // Opening it is what shows it
        player.openInventory(inventory);
        assertSame(inventory, player.getOpenInventory());
        assertEquals(Set.of(player), inventory.getViewers());
    }

    /**
     * A subclass that overrides {@link Inventory#addViewer} to send more packets still has it called when the inventory
     * is opened.
     */
    @Test
    public void anOverriddenAddViewerRunsWhenTheInventoryIsOpened(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        var villager = new VillagerInventory("villager");
        var trades = connection.trackIncoming(TradeListPacket.class);

        assertTrue(player.openInventory(villager));

        trades.assertSingle(packet -> assertEquals(villager.getWindowId(), packet.windowId()));
        assertSame(villager, player.getOpenInventory());
    }

    @Test
    public void anOpenEventCannotBeGivenNullOrAPlayerInventory(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var first = new Inventory(InventoryType.CHEST_1_ROW, "first");
        var second = new Inventory(InventoryType.CHEST_1_ROW, "second");
        player.openInventory(first);

        var nullRefusal = new AtomicReference<Throwable>();
        var playerInventoryRefusal = new AtomicReference<Throwable>();
        var kept = new AtomicReference<AbstractInventory>();
        env.process().eventHandler().addListener(InventoryOpenEvent.class, event -> {
            nullRefusal.set(refusal(() -> setNull(event)));
            playerInventoryRefusal.set(refusal(() -> event.setInventory(player.getInventory())));
            kept.set(event.getInventory());
        });

        assertTrue(player.openInventory(second));

        assertInstanceOf(NullPointerException.class, nullRefusal.get());
        assertInstanceOf(IllegalArgumentException.class, playerInventoryRefusal.get());
        assertTrue(playerInventoryRefusal.get().getMessage().contains("PlayerInventory"),
                playerInventoryRefusal.get().getMessage());
        assertSame(second, kept.get(), "the inventory of the event after the refused setters");
        assertSame(second, player.getOpenInventory());
        assertTrue(first.getViewers().isEmpty(), "viewers of the first inventory");
        assertEquals(Set.of(player), second.getViewers());
    }

    /**
     * A click holds the monitor of the clicked inventory while its events are dispatched to plugins. Opening another,
     * already bound inventory must not need that inventory's monitor, otherwise two players opening each other's
     * inventory from a click on their own deadlock two tick threads.
     */
    @Test
    public void openingABoundInventoryDoesNotWaitForItsMonitor(Env env) throws Exception {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var inventory = new Inventory(env.process(), InventoryType.CHEST_1_ROW, Component.empty());

        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform().start(() -> {
            synchronized (inventory) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        try {
            CompletableFuture.runAsync(() -> player.openInventory(inventory)).get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.join();
        }
        assertSame(inventory, player.getOpenInventory());
    }

    private static Throwable refusal(Runnable runnable) {
        try {
            runnable.run();
            return new AssertionError("not refused");
        } catch (RuntimeException e) {
            return e;
        }
    }

    @SuppressWarnings("NullArgumentForNonNullParameter") // what a careless listener does
    private static void setNull(InventoryOpenEvent event) {
        event.setInventory(null);
    }
}
