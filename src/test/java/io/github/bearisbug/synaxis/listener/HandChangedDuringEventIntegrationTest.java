package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.event.player.PlayerBlockPlaceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A listener of a block placement or of a drop may change what the player holds. The engine takes
 * the placed block, or the dropped item, out of the hand only while the hand still holds what it
 * held before the event: what the listener put there stays. A copy equal to what was held is the
 * same item, and is used up as usual.
 * <p>
 * The player never ticks here: the test thread hands it its packets.
 */
@EnvTest
public class HandChangedDuringEventIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);

    private static void send(Player player, ClientPacket packet) {
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();
    }

    /** Uses the top face of the stone at (2, 39, 0), the block goes to (2, 40, 0). */
    private static void placeOnTheFloor(Player player) {
        send(player, new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(2, 39, 0), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 1));
    }

    private static void drop(Player player, ClientPlayerActionPacket.Status status) {
        send(player, new ClientPlayerActionPacket(status, Vec.ZERO, BlockFace.BOTTOM, 0));
    }

    @Test
    public void aHandTheBlockPlaceListenerChangedIsKept(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createConnection().connect(instance, SPAWN);
        player.setItemInMainHand(ItemStack.of(Material.STONE, 1));
        // An auto refill: the last block of the stack goes down, the next stack takes its place
        env.process().eventHandler().addListener(PlayerBlockPlaceEvent.class,
                event -> event.getPlayer().setItemInMainHand(ItemStack.of(Material.DIAMOND, 3)));

        placeOnTheFloor(player);

        assertEquals(Block.STONE, instance.getBlock(2, 40, 0));
        assertEquals(ItemStack.of(Material.DIAMOND, 3), player.getItemInMainHand());
    }

    @Test
    public void anEqualCopyTheBlockPlaceListenerPutBackIsUsedUp(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createConnection().connect(instance, SPAWN);
        player.setItemInMainHand(ItemStack.of(Material.STONE, 64));
        env.process().eventHandler().addListener(PlayerBlockPlaceEvent.class,
                event -> event.getPlayer().setItemInMainHand(ItemStack.of(Material.STONE, 64)));

        placeOnTheFloor(player);

        assertEquals(Block.STONE, instance.getBlock(2, 40, 0));
        assertEquals(ItemStack.of(Material.STONE, 63), player.getItemInMainHand(), "the placement was free");
    }

    @Test
    public void aHandTheDropListenerChangedIsKept(Env env) {
        final Player player = env.createConnection().connect(env.createFlatInstance(), SPAWN);
        env.process().eventHandler().addListener(ItemDropEvent.class,
                event -> event.getPlayer().setItemInMainHand(ItemStack.of(Material.GOLD_INGOT, 5)));

        player.setItemInMainHand(ItemStack.of(Material.STONE, 64));
        drop(player, ClientPlayerActionPacket.Status.DROP_ITEM);
        assertEquals(ItemStack.of(Material.GOLD_INGOT, 5), player.getItemInMainHand(), "dropping one");

        player.setItemInMainHand(ItemStack.of(Material.STONE, 64));
        drop(player, ClientPlayerActionPacket.Status.DROP_ITEM_STACK);
        assertEquals(ItemStack.of(Material.GOLD_INGOT, 5), player.getItemInMainHand(), "dropping the stack");
    }

    @Test
    public void anEqualCopyTheDropListenerPutBackIsDropped(Env env) {
        final Player player = env.createConnection().connect(env.createFlatInstance(), SPAWN);
        player.setItemInMainHand(ItemStack.of(Material.STONE, 64));
        env.process().eventHandler().addListener(ItemDropEvent.class,
                event -> event.getPlayer().setItemInMainHand(ItemStack.of(Material.STONE, 64)));

        drop(player, ClientPlayerActionPacket.Status.DROP_ITEM);

        assertEquals(ItemStack.of(Material.STONE, 63), player.getItemInMainHand(), "the drop was free");
    }
}
