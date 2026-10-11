package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventListener;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerBlockInteractEvent;
import io.github.bearisbug.synaxis.event.player.PlayerBlockPlaceEvent;
import io.github.bearisbug.synaxis.event.player.PlayerUseItemEvent;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.listener.BlockPlacementListener;
import io.github.bearisbug.synaxis.listener.UseItemListener;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUseItemPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player frozen by its transfer places no block, interacts with none and uses no item ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-005} 冻结, v0.9.73): from {@code PlayerStore#transferOut} until it leaves, the target shard is going
 * to restore the inventory the transfer wrote, so a block placed in between would end up in the world and in the
 * inventory both. The three are cancelled the way a frozen pickup and drop are: on the root node, so a plugin listener
 * in the instance scope already gets them cancelled, and again at priority 3, so a plugin taking the cancel back at
 * {@code HIGHEST} does not get through.
 */
@EnvTest
class FrozenBlockUseIntegrationTest {

    /** The top block of the flat instance's floor. */
    private static final int FLOOR = 39;

    @Test
    void aPlayerFrozenByItsTransferPlacesNoBlockInteractsWithNoneAndUsesNoItem(Env env) {
        final PlayerStore store = new PlayerStore(env.process(), new MemoryBackend(), RedisLink.disabled("memory"),
                "memory", false, PlayerStore.Options.DEFAULT, (_, _) -> {
        });
        final EventNode<Event> host = PlayerStateFixture.hostNode(env);
        store.attach(host);
        final Instance instance = env.createFlatInstance();
        final List<String> seen = new CopyOnWriteArrayList<>();
        // A plugin in the instance scope, one taking the cancel back at HIGHEST and one reading the outcome at MONITOR
        instance.eventNode().addListener(record(PlayerBlockPlaceEvent.class, "place arrived", seen))
                .addListener(record(PlayerBlockInteractEvent.class, "interact arrived", seen))
                .addListener(record(PlayerUseItemEvent.class, "use arrived", seen));
        final EventNode<Event> highest = EventNode.all("highest").setPriority(2);
        highest.addListener(allow(PlayerBlockPlaceEvent.class)).addListener(allow(PlayerBlockInteractEvent.class))
                .addListener(allow(PlayerUseItemEvent.class));
        final EventNode<Event> monitor = EventNode.all("monitor").setPriority(4);
        monitor.addListener(record(PlayerBlockPlaceEvent.class, "place ended", seen))
                .addListener(record(PlayerBlockInteractEvent.class, "interact ended", seen))
                .addListener(record(PlayerUseItemEvent.class, "use ended", seen));
        host.addChild(highest).addChild(monitor);
        final Player player = PlayerStateFixture.online(env, store, instance, UUID.randomUUID(), "Leaver");
        player.setItemInHand(PlayerHand.MAIN, ItemStack.of(Material.STONE, 2));

        use(player, 2, 2, 1);
        assertEquals(outcomes(false), seen.stream().sorted().toList(), "not frozen");
        assertEquals(Block.STONE, instance.getBlock(2, FLOOR + 1, 2), "placed");
        assertEquals(1, player.getItemInMainHand().amount(), "the placed stone left the hand");

        final CompletableFuture<PlayerStore.Handoff> transfer = store.transferOut(player);
        assertNotNull(transfer, "online");
        assertTrue(store.isFrozen(player), "frozen as the transfer begins, before its snapshot");
        seen.clear();
        use(player, 3, 0, 2);
        assertEquals(outcomes(true), seen.stream().sorted().toList(), "frozen before the snapshot");
        assertEquals(Block.AIR, instance.getBlock(3, FLOOR + 1, 0), "nothing placed before the snapshot");

        await(env, transfer::isDone, "the transfer's write");
        assertNotNull(transfer.join(), "written");
        assertTrue(store.isFrozen(player), "still frozen once written, until it leaves");
        seen.clear();
        use(player, 0, 3, 3);
        assertEquals(outcomes(true), seen.stream().sorted().toList(), "frozen once written");
        assertEquals(Block.AIR, instance.getBlock(0, FLOOR + 1, 3), "nothing placed once written");
        assertEquals(1, player.getItemInMainHand().amount(), "the stone the transfer wrote is still in the hand");
        assertFalse(store.isOnline(player));
    }

    /**
     * On the player's tick thread: places the stone in hand on top of the floor at {@code x, z} as a client's packet
     * does, which dispatches a block interaction and then the placement, and uses the item in hand as a client's packet
     * does. The blocks are in the chunk the player spawned in, within its reach.
     */
    private static void use(Player player, int x, int z, int sequence) {
        player.acquirable().sync(_ -> {
            BlockPlacementListener.listener(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Pos(x, FLOOR, z),
                    BlockFace.TOP, 0.5f, 1f, 0.5f, false, false, sequence), player);
            UseItemListener.useItemListener(new ClientUseItemPacket(PlayerHand.MAIN, sequence, 0f, 0f), player);
        });
    }

    private static List<String> outcomes(boolean cancelled) {
        return List.of("interact arrived cancelled=" + cancelled, "interact ended cancelled=" + cancelled,
                "place arrived cancelled=" + cancelled, "place ended cancelled=" + cancelled,
                "use arrived cancelled=" + cancelled, "use ended cancelled=" + cancelled);
    }

    private static <E extends CancellableEvent> EventListener<E> record(Class<E> type, String name, List<String> seen) {
        return EventListener.builder(type).ignoreCancelled(false)
                .handler(event -> seen.add(name + " cancelled=" + event.isCancelled())).build();
    }

    private static <E extends CancellableEvent> EventListener<E> allow(Class<E> type) {
        return EventListener.builder(type).ignoreCancelled(false).handler(event -> event.setCancelled(false)).build();
    }
}
