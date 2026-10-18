package io.github.bearisbug.synaxis.inventory;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.inventory.InventoryBundleItemSelectEvent;
import io.github.bearisbug.synaxis.event.inventory.InventoryItemChangeEvent;
import io.github.bearisbug.synaxis.event.inventory.InventoryOpenEvent;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSelectBundleItemPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityEquipmentPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetCursorItemPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPlayerInventorySlotPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetSlotPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowItemsPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class InventoryIntegrationTest {

    private static final ItemStack MAGIC_STACK = ItemStack.of(Material.DIAMOND, 3);

    @Test
    public void setSlotDuplicateTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        Inventory inventory = new Inventory(InventoryType.CHEST_6_ROW, Component.empty());
        player.openInventory(inventory);
        assertEquals(inventory, player.getOpenInventory());

        var packetTracker = connection.trackIncoming(SetSlotPacket.class);
        inventory.setItemStack(3, MAGIC_STACK);
        packetTracker.assertSingle(slot -> assertEquals(MAGIC_STACK, slot.itemStack())); // Setting a slot should send a packet

        packetTracker = connection.trackIncoming(SetSlotPacket.class);
        inventory.setItemStack(3, MAGIC_STACK);
        packetTracker.assertEmpty(); // Setting the same slot to the same ItemStack should not send another packet

        packetTracker = connection.trackIncoming(SetSlotPacket.class);
        inventory.setItemStack(3, ItemStack.AIR);
        packetTracker.assertSingle(slot -> assertEquals(ItemStack.AIR, slot.itemStack())); // Setting a slot should send a packet
    }

    @Test
    public void setCursorItemDuplicateTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        Inventory inventory = new Inventory(InventoryType.CHEST_6_ROW, Component.empty());
        player.openInventory(inventory);
        assertEquals(inventory, player.getOpenInventory());

        var packetTracker = connection.trackIncoming(SetCursorItemPacket.class);
        player.getInventory().setCursorItem(MAGIC_STACK);
        packetTracker.assertSingle(slot -> assertEquals(MAGIC_STACK, slot.itemStack())); // Setting a slot should send a packet

        packetTracker = connection.trackIncoming(SetCursorItemPacket.class);
        player.getInventory().setCursorItem(MAGIC_STACK);
        packetTracker.assertEmpty(); // Setting the same slot to the same ItemStack should not send another packet

        packetTracker = connection.trackIncoming(SetCursorItemPacket.class);
        player.getInventory().setCursorItem(ItemStack.AIR);
        packetTracker.assertSingle(slot -> assertEquals(ItemStack.AIR, slot.itemStack())); // Setting a slot should send a packet
    }

    @Test
    public void clearInventoryTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        Inventory inventory = new Inventory(InventoryType.CHEST_6_ROW, Component.empty());
        player.openInventory(inventory);
        assertEquals(inventory, player.getOpenInventory());

        var setSlotTracker = connection.trackIncoming(SetSlotPacket.class);
        var setCursorTracker = connection.trackIncoming(SetCursorItemPacket.class);

        inventory.setItemStack(1, MAGIC_STACK);
        inventory.setItemStack(3, MAGIC_STACK);
        inventory.setItemStack(19, MAGIC_STACK);
        inventory.setItemStack(40, MAGIC_STACK);
        player.getInventory().setCursorItem(MAGIC_STACK);

        setSlotTracker.assertCount(4);
        setCursorTracker.assertCount(1);

        setSlotTracker = connection.trackIncoming(SetSlotPacket.class);
        var updateWindowTracker = connection.trackIncoming(WindowItemsPacket.class);
        var equipmentTracker = connection.trackIncoming(EntityEquipmentPacket.class);

        // Perform the clear operation we are testing
        inventory.clear();

        // Make sure not individual SetSlotPackets get sent
        setSlotTracker.assertEmpty();

        // Make sure WindowItemsPacket is empty except for cursor (clearing the player inventory itself clears the cursor)
        updateWindowTracker.assertSingle(windowItemsPacket -> {
            assertEquals(MAGIC_STACK, windowItemsPacket.carriedItem());
            for (ItemStack item : windowItemsPacket.items()) {
                assertEquals(ItemStack.AIR, item);
            }
        });

        // Make sure EntityEquipmentPacket isn't sent (this is an Inventory, not a PlayerInventory)
        equipmentTracker.assertEmpty();
    }

    @Test
    public void clearingPlayerInventoryClearsCursorTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        var setCursorTracker = connection.trackIncoming(SetCursorItemPacket.class);
        player.getInventory().setCursorItem(MAGIC_STACK);
        setCursorTracker.assertCount(1);

        var setSlotTracker = connection.trackIncoming(SetSlotPacket.class);
        var setPlayerSlotTracker = connection.trackIncoming(SetPlayerInventorySlotPacket.class);
        setCursorTracker = connection.trackIncoming(SetCursorItemPacket.class);
        var updateWindowTracker = connection.trackIncoming(WindowItemsPacket.class);
        var equipmentTracker = connection.trackIncoming(EntityEquipmentPacket.class);

        // Perform the clear operation we are testing
        player.getInventory().clear();

        // Make sure no individual set slot/set cursor/set player slot packets get sent
        setSlotTracker.assertEmpty();
        setPlayerSlotTracker.assertEmpty();
        setCursorTracker.assertEmpty();

        // Make sure WindowItemsPacket is empty
        updateWindowTracker.assertSingle(windowItemsPacket -> assertEquals(ItemStack.AIR, windowItemsPacket.carriedItem()));

        // Make sure no EntityEquipmentPacket is sent (nothing was equipped)
        equipmentTracker.assertEmpty();
    }

    @Test
    public void closeInventoryTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        final var inventory = new Inventory(InventoryType.CHEST_1_ROW, "title");
        player.openInventory(inventory);
        assertSame(inventory, player.getOpenInventory());
        player.closeInventory();
        assertNull(player.getOpenInventory());
    }

    @Test
    public void openInventoryOnItemDropFromInventoryClosingTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        var listener = env.listen(ItemDropEvent.class);
        final var firstInventory = new Inventory(InventoryType.CHEST_1_ROW, "title");
        player.openInventory(firstInventory);
        assertSame(firstInventory, player.getOpenInventory());
        player.getInventory().setCursorItem(ItemStack.of(Material.STONE));

        listener.followup();
        player.closeInventory();
        assertNull(player.getOpenInventory());

        player.openInventory(firstInventory);
        player.getInventory().setCursorItem(ItemStack.of(Material.STONE));
        final var secondInventory = new Inventory(InventoryType.CHEST_1_ROW, "title");
        listener.followup(event -> event.getPlayer().openInventory(secondInventory));
        player.closeInventory();
        assertSame(secondInventory, player.getOpenInventory());
    }

    @Test
    public void testInnerInventorySlotSending(Env env) {
        // Inner inventory changes are sent along with the open inventory
        // Otherwise, they are sent separately

        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        Inventory inventory = new Inventory(InventoryType.CHEST_6_ROW, Component.empty());
        player.openInventory(inventory);
        assertEquals(inventory, player.getOpenInventory());

        // Ensure that slots not in the inner inventory are sent separately
        var packetTracker = connection.trackIncoming(SetPlayerInventorySlotPacket.class);
        player.getInventory().setItemStack(PlayerInventoryUtils.OFFHAND_SLOT, MAGIC_STACK);
        packetTracker.assertSingle(slot -> {
            assertEquals(40, slot.slot()); // Off hand is slot 40 in player inventory
            assertEquals(MAGIC_STACK, slot.itemStack());
        });

        // Ensure that inner inventory slots are sent as the opened inventory
        packetTracker = connection.trackIncoming(SetPlayerInventorySlotPacket.class);
        player.getInventory().setItemStack(0, MAGIC_STACK); // Test with first inner inventory slot
        packetTracker.assertSingle(slot -> {
            assertEquals(0, slot.slot());
            assertEquals(MAGIC_STACK, slot.itemStack());
        });

        packetTracker = connection.trackIncoming(SetPlayerInventorySlotPacket.class);
        player.getInventory().setItemStack(35, MAGIC_STACK); // Test with last inner inventory slot
        packetTracker.assertSingle(slot -> {
            assertEquals(35, slot.slot());
            assertEquals(MAGIC_STACK, slot.itemStack());
        });
    }

    @Test
    public void testEventNode(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();

        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        Inventory inventory = new Inventory(env.process(), InventoryType.CHEST_6_ROW, Component.empty());
        AtomicBoolean called = new AtomicBoolean(false);
        inventory.eventNode().addListener(
                InventoryOpenEvent.class,
                event -> {
                    assertSame(inventory, event.getInventory());
                    called.set(true);
                }
        );

        player.openInventory(inventory);
        assertTrue(called.get(), "InventoryOpenEvent not fired");
    }

    @Test
    public void bundleActivateTestLowerInventory(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        final var inventory = new Inventory(InventoryType.CHEST_6_ROW, "title");
        player.openInventory(inventory);
        // No bundles are set - they might not exist serverside and be sent directly to the client

        var listenerLowerInv = env.trackEvent(InventoryBundleItemSelectEvent.class, EventFilter.PLAYER, player);
        player.addPacketToQueue(new ClientSelectBundleItemPacket(inventory.getSize() + 2, 42));
        player.interpretPacketQueue();
        listenerLowerInv.assertSingle(event -> {
            assertEquals(11, event.getSlot());
            assertEquals(player.getInventory(), event.getInventory());
        });
    }

    @Test
    public void bundleActivateTestUpperInventory(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        final var inventory = new Inventory(InventoryType.CHEST_3_ROW, "title"); // size = 27
        player.openInventory(inventory);

        var listener = env.trackEvent(InventoryBundleItemSelectEvent.class, EventFilter.PLAYER, player);
        // Window slot 27 (27 + 0) corresponds to player inventory main slot 9 in the engine
        player.addPacketToQueue(new ClientSelectBundleItemPacket(27, 42));
        player.interpretPacketQueue();

        listener.assertSingle(event -> {
            assertEquals(9, event.getSlot()); // Expected: slot 9 (main inventory slot 9)
            assertEquals(player.getInventory(), event.getInventory());
        });
    }

    @Test
    public void bundleActivateTestNoContainerOpen(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        var listener = env.trackEvent(InventoryBundleItemSelectEvent.class, EventFilter.PLAYER, player);
        // Window slot 36 corresponds to hotbar slot 0 in the engine (when openInventory == null)
        player.addPacketToQueue(new ClientSelectBundleItemPacket(36, 42));
        player.interpretPacketQueue();

        listener.assertSingle(event -> {
            assertEquals(0, event.getSlot()); // Expected: slot 0 (hotbar slot 0)
            assertEquals(player.getInventory(), event.getInventory());
        });
    }

    @Test
    public void bundleActivateTestInventorySizeSlot(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        var listener = env.trackEvent(InventoryBundleItemSelectEvent.class, EventFilter.PLAYER, player);
        // Window slot 46 would be engine slot 46, one past the last slot of the player inventory (45)
        player.addPacketToQueue(new ClientSelectBundleItemPacket(46, 42));
        player.interpretPacketQueue();

        listener.assertEmpty();
    }

    @Test
    public void bundleActivateTestInventorySizeSlotContainer(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        final var inventory = new Inventory(InventoryType.CHEST_3_ROW, "title");
        player.openInventory(inventory);

        var listener = env.trackEvent(InventoryBundleItemSelectEvent.class, EventFilter.PLAYER, player);
        // Window slot 64 would be engine slot 46, one past the last slot of the player inventory (45)
        player.addPacketToQueue(new ClientSelectBundleItemPacket(64, 42));
        player.interpretPacketQueue();

        listener.assertEmpty();
    }

    @Test
    public void containerInventoryIsBoundWhenOpened(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        Inventory inventory = new Inventory(InventoryType.CHEST_6_ROW, "title");
        assertNull(inventory.process(), "an inventory never shown to a player belongs to no process");

        player.openInventory(inventory);
        assertSame(env.process(), inventory.process());

        AtomicReference<InventoryItemChangeEvent> received = new AtomicReference<>();
        inventory.eventNode().addListener(InventoryItemChangeEvent.class, received::set);
        inventory.setItemStack(3, MAGIC_STACK);

        InventoryItemChangeEvent event = received.get();
        assertNotNull(event, "InventoryItemChangeEvent has not been fired");
        assertEquals(3, event.getSlot());
        assertEquals(MAGIC_STACK, event.getNewItem());
    }

    @Test
    public void bundleActivateTestInvalidPacketIgnored(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        var listener = env.trackEvent(InventoryBundleItemSelectEvent.class, EventFilter.PLAYER, player);
        player.addPacketToQueue(new ClientSelectBundleItemPacket(0, -2)); // Selected index below -1
        player.addPacketToQueue(new ClientSelectBundleItemPacket(-1, 0)); // Negative slot
        player.addPacketToQueue(new ClientSelectBundleItemPacket(100, 0)); // Outside of the player inventory
        player.interpretPacketQueue();

        listener.assertEmpty();
    }

    @Test
    public void bundleActivateTestInvalidPacketIgnoredContainer(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.openInventory(new Inventory(InventoryType.CHEST_3_ROW, "title"));

        var listener = env.trackEvent(InventoryBundleItemSelectEvent.class, EventFilter.PLAYER, player);
        player.addPacketToQueue(new ClientSelectBundleItemPacket(100, 0)); // Outside of the player inventory
        player.interpretPacketQueue();

        listener.assertEmpty();
    }
}
