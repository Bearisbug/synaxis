package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.inventory.CreativeInventoryActionEvent;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.listener.CreativeInventoryActionListener;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCreativeInventoryActionPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class PlayerCreativeSlotIntegrationTest {

    @Test
    public void testCreativeSlots(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        player.setGameMode(GameMode.CREATIVE);
        player.addPacketToQueue(new ClientCreativeInventoryActionPacket((short) PlayerInventoryUtils.OFFHAND_SLOT, ItemStack.of(Material.STICK)));
        player.interpretPacketQueue();
        assertEquals(Material.STICK, player.getItemInOffHand().material());
    }

    @Test
    public void testBoundsCheck(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.setGameMode(GameMode.CREATIVE);

        assertDoesNotThrow(() -> CreativeInventoryActionListener.listener(new ClientCreativeInventoryActionPacket((short) 76, ItemStack.of(Material.OAK_LOG)), player));
    }

    /**
     * Slot -1 is a drop out of the creative menu: the event reports -1 rather than a slot, and cancelling it drops
     * nothing.
     */
    @Test
    public void aDropOutOfTheCreativeMenuReportsSlotMinusOne(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.setGameMode(GameMode.CREATIVE);
        var actions = new ArrayList<CreativeInventoryActionEvent>();
        env.process().eventHandler().addListener(CreativeInventoryActionEvent.class, event -> {
            actions.add(event);
            event.setCancelled(true);
        });
        var drops = env.trackEvent(ItemDropEvent.class, EventFilter.PLAYER, player);

        player.addPacketToQueue(new ClientCreativeInventoryActionPacket((short) -1, ItemStack.of(Material.STICK)));
        player.interpretPacketQueue();

        assertEquals(1, actions.size(), "creative inventory actions");
        assertEquals(-1, actions.getFirst().getSlot());
        assertEquals(ItemStack.of(Material.STICK), actions.getFirst().getClickedItem());
        drops.assertEmpty();
    }
}
