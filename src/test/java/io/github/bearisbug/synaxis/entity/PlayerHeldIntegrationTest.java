package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.player.PlayerChangeHeldSlotEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientHeldItemChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.HeldItemChangePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class PlayerHeldIntegrationTest {

    @Test
    void playerHeld(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        player.getInventory().setItemStack(1, ItemStack.of(Material.STONE));
        assertEquals(ItemStack.AIR, player.getItemInMainHand());
        assertEquals(0, player.getHeldSlot());

        player.addPacketToQueue(new ClientHeldItemChangePacket((short) 1));
        player.interpretPacketQueue();

        assertEquals(ItemStack.of(Material.STONE), player.getItemInMainHand());
        assertEquals(1, player.getHeldSlot());
    }

    @Test
    void playerHeldEvent(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        player.getInventory().setItemStack(1, ItemStack.of(Material.STONE));
        assertEquals(ItemStack.AIR, player.getItemInMainHand());
        assertEquals(0, player.getHeldSlot());

        player.addPacketToQueue(new ClientHeldItemChangePacket((short) 1));
        var listener = env.listen(PlayerChangeHeldSlotEvent.class);
        listener.followup(event -> {
            assertEquals(player, event.getPlayer());
            assertEquals(0, event.getOldSlot());
            assertEquals(1, event.getNewSlot());
        });
        player.interpretPacketQueue();
        assertEquals(ItemStack.of(Material.STONE), player.getItemInMainHand());
        assertEquals(1, player.getHeldSlot());
    }

    @Test
    void playerChangingSlots(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        player.getInventory().setItemStack(1, ItemStack.of(Material.STONE));
        player.getInventory().setItemStack(3, ItemStack.of(Material.OAK_PLANKS));

        player.addPacketToQueue(new ClientHeldItemChangePacket((short) 1));
        var listener = env.listen(PlayerChangeHeldSlotEvent.class);
        listener.followup(event -> {
            assertEquals(player, event.getPlayer());
            assertEquals(0, event.getOldSlot());
            assertEquals(1, event.getNewSlot());
            assertEquals(ItemStack.AIR, event.getItemInOldSlot());
            assertEquals(ItemStack.of(Material.STONE), event.getItemInNewSlot());
        });
        player.interpretPacketQueue();

        player.addPacketToQueue(new ClientHeldItemChangePacket((short) 3));
        listener.followup(event -> {
            assertEquals(player, event.getPlayer());
            assertEquals(1, event.getOldSlot());
            assertEquals(3, event.getNewSlot());
            assertEquals(ItemStack.of(Material.STONE), event.getItemInOldSlot());
            assertEquals(ItemStack.of(Material.OAK_PLANKS), event.getItemInNewSlot());
        });
        player.interpretPacketQueue();
    }

    @Test
    void eventChangeIsReflectedOnClient(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        player.eventNode().addListener(PlayerChangeHeldSlotEvent.class, event -> event.setNewSlot((byte) 0));

        var listener = connection.trackIncoming(HeldItemChangePacket.class);
        player.addPacketToQueue(new ClientHeldItemChangePacket((short) 0));
        player.interpretPacketQueue();
        listener.assertEmpty(); // Ensure we don't send an unneeded packet if there is no change

        listener = connection.trackIncoming(HeldItemChangePacket.class); // Re-register listener
        player.addPacketToQueue(new ClientHeldItemChangePacket((short) 3));
        player.interpretPacketQueue();
        listener.assertSingle(packet -> assertEquals((byte) 0, packet.slot())); // Ensure packet is sent
    }
}
