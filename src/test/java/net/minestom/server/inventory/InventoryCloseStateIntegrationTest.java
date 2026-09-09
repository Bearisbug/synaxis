package net.minestom.server.inventory;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.client.play.ClientCloseWindowPacket;
import net.minestom.server.network.packet.server.play.CloseWindowPacket;
import net.minestom.server.network.packet.server.play.OpenWindowPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class InventoryCloseStateIntegrationTest {


    @Test
    public void doNotReceiveClosePacketFromServerWhenSendingClientCloseWindowPacket(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        var packetTracker = connection.trackIncoming(CloseWindowPacket.class);
        var inventory = new Inventory(InventoryType.CHEST_2_ROW, Component.text("Test"));
        player.openInventory(inventory);
        player.closeInventory(); // Closes the inventory server-side, should send a CloseWindowPacket
        player.openInventory(inventory);
        // Send the close window packet
        player.addPacketToQueue(new ClientCloseWindowPacket(inventory.getWindowId()));
        player.interpretPacketQueue();
        packetTracker.assertSingle(closeWindowPacket -> assertEquals(inventory.getWindowId(), closeWindowPacket.windowId()));
        packetTracker.assertCount(1); // Assert we only get 1 close window packet from the closeInventory(); call
    }

    /**
     * A client that reports closing window 0 while a container is open must not drop the container reference: the
     * container would keep the player as a viewer forever and could never be opened again.
     */
    @Test
    public void clientCloseWindowZeroMustNotLeakOpenContainer(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        var inventory = new Inventory(InventoryType.CHEST_1_ROW, Component.text("Test"));
        player.openInventory(inventory);

        player.addPacketToQueue(new ClientCloseWindowPacket(0));
        player.interpretPacketQueue();

        assertSame(inventory, player.getOpenInventory(), "window 0 must not close an open container");
        assertTrue(inventory.getViewers().contains(player));

        // The container is still in a sane state: it can be closed and re-opened normally
        player.addPacketToQueue(new ClientCloseWindowPacket(inventory.getWindowId()));
        player.interpretPacketQueue();

        assertNull(player.getOpenInventory());
        assertFalse(inventory.getViewers().contains(player));

        var openWindowTracker = connection.trackIncoming(OpenWindowPacket.class);
        assertTrue(player.openInventory(inventory));
        openWindowTracker.assertCount(1);
    }
}