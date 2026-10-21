package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerEventTraitIntegrationTest {

    @Test
    public void aPlayerEventReportsThePlayerAsItsEntity(Env env) {
        var instance = env.createEmptyInstance();
        var player = env.createPlayer(instance, Pos.ZERO);
        var event = new PlayerGameRulesRequestEvent(player);

        assertSame(player, event.getPlayer());
        assertSame(player, event.getEntity(), "PlayerEvent derives its entity from the player");
        assertSame(instance, event.getInstance(), "PlayerInstanceEvent derives its instance from the player");
        assertSame(player, EventFilter.PLAYER.getHandler(event));
        assertSame(player, EventFilter.ENTITY.getHandler(event));
    }

    @Test
    public void theHeldSlotEventReadsTheInventoryOfItsPlayer(Env env) {
        var instance = env.createEmptyInstance();
        var player = env.createPlayer(instance, Pos.ZERO);
        player.getInventory().setItemStack(0, ItemStack.of(Material.DIAMOND));
        player.getInventory().setItemStack(4, ItemStack.of(Material.STONE));

        var event = new PlayerChangeHeldSlotEvent(player, (byte) 0, (byte) 4);
        assertEquals(ItemStack.of(Material.DIAMOND), event.getItemInOldSlot());
        assertEquals(ItemStack.of(Material.STONE), event.getItemInNewSlot());

        event.setNewSlot((byte) 8);
        assertSame(ItemStack.AIR, event.getItemInNewSlot());
    }

    @Test
    public void aTransferRefusesAMissingHostButKeepsItsPlayer(Env env) {
        var instance = env.createEmptyInstance();
        var player = env.createPlayer(instance, Pos.ZERO);
        var event = new OutgoingTransferEvent(player, "example.com", 25565);

        assertSame(player, event.getPlayer());
        assertThrows(NullPointerException.class, () -> event.setHost(null));
        assertEquals("example.com", event.getHost());
    }

    @Test
    public void aTransferRefusesABlankHostOrAPortOutOfRange(Env env) {
        var instance = env.createEmptyInstance();
        var player = env.createPlayer(instance, Pos.ZERO);
        var event = new OutgoingTransferEvent(player, "example.com", 25565);

        for (int port : new int[]{0, -1, 65536, 70000}) {
            var refused = assertThrows(IllegalArgumentException.class, () -> event.setPort(port));
            assertTrue(refused.getMessage().endsWith("got " + port), refused.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> event.setHost(" "));
        assertEquals("example.com", event.getHost(), "a refused value leaves the event as it was");
        assertEquals(25565, event.getPort());

        event.setPort(1);
        assertEquals(1, event.getPort());
        event.setPort(65535);
        assertEquals(65535, event.getPort());

        assertThrows(IllegalArgumentException.class, () -> new OutgoingTransferEvent(player, "", 25565));
        assertThrows(IllegalArgumentException.class, () -> new OutgoingTransferEvent(player, "example.com", 0));
    }
}
