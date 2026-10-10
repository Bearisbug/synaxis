package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCloseWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DROP} of the default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}): the three ways a client
 * drops an item all reach {@code Player#dropItem}, and each one that nobody cancelled spawns an item entity at the
 * player's eyes, thrown along the look direction at 6 blocks a second and not pickable for 40 ticks.
 */
@EnvTest
class DefaultPlayDropIntegrationTest {

    /**
     * Standing on the floor of the flat test world, looking along +x.
     */
    private static final Pos EAST = new Pos(0.5, 40, 0.5, -90, 0);

    @Test
    void everyDropPathSpawnsAnItemEntityAtTheEyes(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.DROP), null);
        final Player player = env.createPlayer(instance, EAST);
        final PlayerInventory inventory = player.getInventory();
        inventory.setItemStack(0, ItemStack.of(Material.STONE, 64));

        // The Q key on the hotbar drops one
        player.addPacketToQueue(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.DROP_ITEM, Vec.ZERO,
                BlockFace.BOTTOM, 0));
        player.interpretPacketQueue();
        assertEquals(1, PlayFixture.items(instance).size(), "item entities after the Q key");
        // The other 63 onto the cursor, one of them into hotbar slot 1, and a left click outside drops the 62 left
        click(player, 0, 0);
        click(player, 1, 1);
        player.addPacketToQueue(new ClientClickWindowPacket(0, 0, (short) -999, (byte) 0,
                ClientClickWindowPacket.ClickType.PICKUP, Map.of(), ItemStack.Hash.AIR));
        player.interpretPacketQueue();
        assertEquals(2, PlayFixture.items(instance).size(), "item entities after the click outside");
        // The one in slot 1 onto the cursor, and the client closes its own inventory with it there
        click(player, 1, 0);
        player.addPacketToQueue(new ClientCloseWindowPacket(0));
        player.interpretPacketQueue();

        final List<ItemEntity> items = PlayFixture.items(instance);
        assertEquals(List.of(1, 1, 62), items.stream().map(item -> item.getItemStack().amount()).sorted().toList(),
                "the stacks of the item entities");
        final Pos eyes = EAST.add(0, player.getEyeHeight(), 0);
        for (ItemEntity item : items) {
            assertEquals(Material.STONE, item.getItemStack().material());
            assertTrue(item.getPosition().samePoint(eyes), "spawned at " + item.getPosition() + ", the eyes are at " + eyes);
            final Vec velocity = item.getVelocity();
            assertEquals(6, velocity.x(), 1e-9, "blocks a second along +x");
            assertEquals(0, velocity.y(), 1e-9);
            assertEquals(0, velocity.z(), 1e-9);
            assertEquals(2_000, item.getPickupDelay(), "40 ticks, in milliseconds");
            assertFalse(item.isPickable(), "pickable right after the drop");
        }
        assertEquals(ItemStack.AIR, inventory.getCursorItem(), "the cursor once the inventory is closed");
        assertTrue(Arrays.stream(inventory.getItemStacks()).allMatch(ItemStack::isAir),
                "the inventory still holds " + Arrays.toString(inventory.getItemStacks()));
    }

    /**
     * A drop cancelled by a listener after the host's own, where a global scope plugin listener at {@code HIGHEST}
     * runs, spawns nothing and leaves the item in the hand: an item entity spawned before that listener ran would be a
     * copy of the item it kept.
     */
    @Test
    void aDropCancelledAfterTheHostSpawnsNothing(Env env) {
        final Instance instance = env.createFlatInstance();
        final EventNode<Event> hostNode = PlayFixture.hostNode(env);
        PlayFixture.attach(env, hostNode, EnumSet.of(Feature.DROP), null);
        PlayFixture.pluginTier(hostNode, 2).addListener(ItemDropEvent.class, event -> event.setCancelled(true));
        final Player player = env.createPlayer(instance, EAST);
        player.getInventory().setItemStack(0, ItemStack.of(Material.STONE, 64));

        player.addPacketToQueue(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.DROP_ITEM, Vec.ZERO,
                BlockFace.BOTTOM, 0));
        player.interpretPacketQueue();

        assertEquals(List.of(), PlayFixture.items(instance), "item entities after a cancelled drop");
        assertEquals(ItemStack.of(Material.STONE, 64), player.getInventory().getItemStack(0));
    }

    /**
     * Without {@code DROP}, as under a gameplay plugin, a drop still takes the item away and spawns nothing: the
     * engine's own behaviour, which the layer only adds to.
     */
    @Test
    void withoutTheFeatureADropSpawnsNothing(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.CHAT), null);
        final Player player = env.createPlayer(instance, EAST);
        player.getInventory().setItemStack(0, ItemStack.of(Material.STONE, 64));

        player.addPacketToQueue(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.DROP_ITEM, Vec.ZERO,
                BlockFace.BOTTOM, 0));
        player.interpretPacketQueue();

        assertEquals(List.of(), PlayFixture.items(instance), "item entities with the chat alone");
    }

    /**
     * A click in window 0 on an engine slot of the player's inventory.
     */
    private static void click(Player player, int slot, int button) {
        player.addPacketToQueue(new ClientClickWindowPacket(0, 0,
                (short) PlayerInventoryUtils.convertEngineSlotToWindowSlot(slot), (byte) button,
                ClientClickWindowPacket.ClickType.PICKUP, Map.of(), ItemStack.Hash.AIR));
        player.interpretPacketQueue();
    }
}
