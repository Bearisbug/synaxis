package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.app.shard.HeartbeatReader;
import io.github.bearisbug.synaxis.app.shard.HeartbeatRow;
import io.github.bearisbug.synaxis.app.shard.ShardDirectory;
import io.github.bearisbug.synaxis.app.shard.ShardGates;
import io.github.bearisbug.synaxis.app.shard.ShardServiceImpl;
import io.github.bearisbug.synaxis.app.transfer.TicketIssuer;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.EventListener;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A gate walk in onto an item copies nothing, whatever plugin listener hands the stack over ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-005}; security review 2026-10 S3-V01, the probe of commit {@code 7062cfecd2}). The stack is handed
 * over by a listener that honours earlier cancels and would run before the store's freeze node: an instance scoped
 * one (the instance's own node is dispatched before the host node, whatever its priority) or a global one below
 * priority 3, built the way {@code PluginEventsImpl} builds its nodes. The player walks into a gate frame onto an
 * item with one position packet on 4 grid tick threads: the gate freezes it in the packet handling, and the pickup
 * of the same update must reach those listeners already cancelled. Otherwise they hand the stack over, the freeze
 * cancels the pickup after them, the transfer snapshot of the next tick takes the grant and the item stays on the
 * ground. A drop in the same batch of packets, after the move, reaches even an instance scoped {@code LOWEST} listener,
 * the first plugin listener of all, already cancelled.
 * <p>
 * In production the gate's heartbeat read completes on a virtual thread ({@code ShardHeartbeat}), and the transfer
 * freezes the player on that thread, whenever it completes. The last two cases complete it from inside a global
 * {@code NORMAL} listener, so that the freeze lands in the middle of a pickup or a drop the root node let through:
 * the event must go through as the listeners before saw it, rather than be cancelled under a grant already made.
 */
class GateFreezeEarlyGrantTest {

    private static final String SOURCE = "r0-c1";
    private static final String TARGET = "r1-c1";
    private static final TransferKeys KEYS = TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
    /** The gate of r0, a frame around x 4..8 next to the spawn at x 0. */
    private static final ShardDirectory DIRECTORY = new ShardDirectory("synth", List.of(
            new ShardDirectory.Region("r0", new SynxManifest.Bounds(-8, -8, 7, 7), List.of(new ShardDirectory.Gate("r0-east",
                    new ShardDirectory.Frame(4, 0, -4, 8, 255, 4), "r1", new Pos(0.5, 42, 0.5)))),
            new ShardDirectory.Region("r1", new SynxManifest.Bounds(8, -8, 23, 7), List.of())),
            List.of(new ShardDirectory.Channel(SOURCE, "r0", 1, "127.0.0.1", 25601, 200),
                    new ShardDirectory.Channel(TARGET, "r1", 1, "127.0.0.1", 25602, 200)),
            SOURCE);
    private static final HeartbeatRow OPEN = new HeartbeatRow(TARGET, "r1", 1, "127.0.0.1", 25602, 0, 200, true, true);
    private static final Vec ITEM_AT = new Vec(6.5, 40, 0.5);
    private static final int AMOUNT = 5;

    /**
     * Heartbeats that answer at once, so that the gate reaches TicketIssuer and the freeze inside the move; or, once a
     * case holds them back in {@link #late}, when the case completes it.
     */
    private final class Heartbeats implements HeartbeatReader {
        @Override
        public CompletableFuture<List<HeartbeatRow>> region(String mapId, String regionId) {
            final CompletableFuture<List<HeartbeatRow>> held = late;
            return held != null ? held : CompletableFuture.completedFuture(List.of(OPEN));
        }

        @Override
        public CompletableFuture<@Nullable HeartbeatRow> shard(String mapId, String shardId) {
            return CompletableFuture.completedFuture(OPEN);
        }
    }

    /** Where the stack is once the transfer is out: handed over into the purse and written with it, or on the ground. */
    private record Walk(int written, int onTheGround) {
        @Override
        public String toString() {
            return "written with the transfer " + written + ", on the ground " + onTheGround;
        }
    }

    private final MemoryBackend backend = new MemoryBackend();
    private final List<String> ledger = new CopyOnWriteArrayList<>();
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile @Nullable CompletableFuture<List<HeartbeatRow>> late;
    private Env env;
    private Instance instance;
    private PlayerStore store;
    private EventNode<Event> hostNode;

    @BeforeEach
    void start() {
        env = Env.create(settings -> settings.dispatcherThreads(4)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID)
                .threadGridExponent(1));
        instance = env.createFlatInstance();
        store = new PlayerStore(env.process(), backend, RedisLink.disabled(SOURCE), SOURCE, false, StoreFixture.FAST,
                (_, _) -> {});
        store.register(StoreFixture.PURSE);
        hostNode = EventNode.all("store-" + SOURCE);
        env.process().eventHandler().addChild(hostNode);
        store.attach(hostNode);
        final TicketIssuer issuer = new TicketIssuer(store, KEYS, new TransferCooldowns(5_000, new AtomicLong(1_000_000)::get),
                SOURCE, (event, attributes) -> ledger.add(event + " " + attributes));
        new ShardGates(DIRECTORY, new ShardServiceImpl(DIRECTORY, new Heartbeats(), issuer),
                (event, attributes) -> ledger.add(event + " " + attributes), store::isOnline).attach(instance);
    }

    @AfterEach
    void stop() {
        env.process().stop();
    }

    /** {@code forInstance(instance)} at {@code MONITOR}: a node of priority 4 below the instance's event node. */
    @Test
    void anInstanceScopedMonitorGrantOfAGateWalkInCopiesNothing() throws Exception {
        final EventNode<InstanceEvent> scoped = EventNode.type("plugin/grant@" + instance.getUuid() + "/monitor",
                EventFilter.INSTANCE).setPriority(4);
        scoped.addListener(grant("instance:monitor", () -> {}));
        instance.eventNode().addChild(scoped);
        assertNothingHandedOver(walkOntoTheItemThroughTheGate());
    }

    /** A global {@code NORMAL} listener: a node of priority 0 below the host node, as the freeze node is. */
    @Test
    void aGlobalNormalGrantOfAGateWalkInCopiesNothing() throws Exception {
        final EventNode<Event> normal = EventNode.all("plugin/grant/normal").setPriority(0);
        normal.addListener(grant("global:normal", () -> {}));
        hostNode.addChild(normal);
        assertNothingHandedOver(walkOntoTheItemThroughTheGate());
    }

    /**
     * The 5 diamonds in hand are dropped in the batch of the walk in, after the move the gate freezes the player in; an
     * instance scoped {@code LOWEST} listener spawns the dropped stack unless an earlier listener cancelled the drop.
     */
    @Test
    void aDropInTheBatchOfAGateWalkInReachesTheFirstListenerCancelled() throws Exception {
        final List<ItemEntity> spawned = new CopyOnWriteArrayList<>();
        final EventNode<InstanceEvent> lowest = EventNode.type("plugin/drop@" + instance.getUuid() + "/lowest",
                EventFilter.INSTANCE).setPriority(-2);
        lowest.addListener(EventListener.builder(ItemDropEvent.class).ignoreCancelled(false).handler(event -> {
            seen.add("instance:lowest cancelled=" + event.isCancelled());
            if (event.isCancelled()) return;
            final ItemEntity item = new ItemEntity(event.getItemStack());
            item.setInstance(instance, event.getPlayer().getPosition()).join();
            spawned.add(item);
        }).build());
        instance.eventNode().addChild(lowest);
        final EventNode<Event> monitor = EventNode.all("plugin/drop/monitor").setPriority(4);
        monitor.addListener(EventListener.builder(ItemDropEvent.class).ignoreCancelled(false)
                .handler(event -> seen.add("global:monitor cancelled=" + event.isCancelled())).build());
        hostNode.addChild(monitor);

        final Player player = aPlayerInTheWorld();
        player.setItemInMainHand(ItemStack.of(Material.DIAMOND, AMOUNT));
        walkIntoTheGate(player, new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.DROP_ITEM_STACK, Vec.ZERO,
                BlockFace.BOTTOM, 0));

        final ItemStack held = player.getItemInMainHand();
        final int inHand = held.isAir() ? 0 : held.amount();
        final int onTheGround = spawned.stream().filter(item -> !item.isRemoved())
                .mapToInt(item -> item.getItemStack().amount()).sum();
        System.out.println("after the walk in: in hand " + inHand + ", spawned " + onTheGround + ", " + seen);
        assertEquals(AMOUNT, inHand + onTheGround, "diamonds after the walk in (in hand " + inHand + ", spawned "
                + onTheGround + "): " + seen);
        assertEquals(List.of("instance:lowest cancelled=true", "global:monitor cancelled=true"), seen,
                "the drop reached the first listener cancelled and nothing was dropped");
    }

    /** The global {@code NORMAL} grant completes the heartbeat read before it hands the stack over. */
    @Test
    void aGateFreezeLandingInThePickupCopiesNothing() throws Exception {
        late = new CompletableFuture<>();
        final EventNode<Event> normal = EventNode.all("plugin/grant/normal").setPriority(0);
        normal.addListener(grant("global:normal", this::completeTheHeartbeatRead));
        hostNode.addChild(normal);
        final Walk walk = walkOntoTheItemThroughTheGate();
        assertTrue(seen.contains("global:normal granted 5 frozen=true"),
                "the freeze landed while the grant had the pickup: " + seen);
        assertEquals(AMOUNT, walk.written() + walk.onTheGround(), "diamonds after the walk in (" + walk + "): " + seen);
        assertEquals(List.of("global:normal granted 5 frozen=true", "global:monitor cancelled=false"), seen,
                "the pickup goes through as the root node let it");
    }

    /**
     * The 5 diamonds in hand are dropped in the batch of the walk in; a global {@code NORMAL} listener completes the
     * heartbeat read, then spawns the dropped stack, as a plugin that drops items into the world does.
     */
    @Test
    void aGateFreezeLandingInTheDropCopiesNothing() throws Exception {
        late = new CompletableFuture<>();
        final List<ItemEntity> spawned = new CopyOnWriteArrayList<>();
        final EventNode<Event> normal = EventNode.all("plugin/drop/normal").setPriority(0);
        normal.addListener(EventListener.builder(ItemDropEvent.class).ignoreCancelled(false).handler(event -> {
            if (event.isCancelled()) return;
            completeTheHeartbeatRead();
            seen.add("global:normal dropped " + event.getItemStack().amount() + " frozen=" + store.isFrozen(event.getPlayer()));
            final ItemEntity item = new ItemEntity(event.getItemStack());
            item.setInstance(instance, event.getPlayer().getPosition()).join();
            spawned.add(item);
        }).build());
        hostNode.addChild(normal);
        final EventNode<Event> monitor = EventNode.all("plugin/drop/monitor").setPriority(4);
        monitor.addListener(EventListener.builder(ItemDropEvent.class).ignoreCancelled(false)
                .handler(event -> seen.add("global:monitor cancelled=" + event.isCancelled())).build());
        hostNode.addChild(monitor);

        final Player player = aPlayerInTheWorld();
        player.setItemInMainHand(ItemStack.of(Material.DIAMOND, AMOUNT));
        walkIntoTheGate(player, new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.DROP_ITEM_STACK, Vec.ZERO,
                BlockFace.BOTTOM, 0));

        final ItemStack held = player.getItemInMainHand();
        final int inHand = held.isAir() ? 0 : held.amount();
        final int onTheGround = spawned.stream().filter(item -> !item.isRemoved()).mapToInt(item -> item.getItemStack().amount()).sum();
        System.out.println("after the walk in: in hand " + inHand + ", spawned " + onTheGround + ", " + seen);
        assertTrue(seen.contains("global:normal dropped 5 frozen=true"),
                "the freeze landed while the listener had the drop: " + seen);
        assertEquals(AMOUNT, inHand + onTheGround, "diamonds after the walk in (in hand " + inHand + ", spawned "
                + onTheGround + "): " + seen);
        assertEquals(List.of("global:normal dropped 5 frozen=true", "global:monitor cancelled=false"), seen,
                "the drop goes through as the root node let it");
    }

    private Walk walkOntoTheItemThroughTheGate() throws InterruptedException {
        // A global MONITOR: it records how each pickup ends
        final EventNode<Event> monitor = EventNode.all("plugin/grant/monitor").setPriority(4);
        monitor.addListener(EventListener.builder(PickupItemEvent.class).ignoreCancelled(false)
                .handler(event -> seen.add("global:monitor cancelled=" + event.isCancelled())).build());
        hostNode.addChild(monitor);

        final Player player = aPlayerInTheWorld();
        final ItemEntity item = new ItemEntity(ItemStack.of(Material.DIAMOND, AMOUNT));
        item.setInstance(instance, new Pos(ITEM_AT.x(), ITEM_AT.y(), ITEM_AT.z())).join();
        for (int i = 0; i < 3; i++) env.tick();
        assertTrue(!item.isRemoved() && seen.isEmpty(), "the item lies in the frame, out of the player's reach: " + seen);

        // The pickup cooldown of 5 ticks is due at the tick of the walk in
        Thread.sleep(300);
        walkIntoTheGate(player);

        final StoreBackend.LoadOutcome.Acquired stored =
                (StoreBackend.LoadOutcome.Acquired) backend.load(player.getUuid(), "Copier", 0);
        // A purse nothing was handed into stays clean, and the transfer writes only dirty sections
        final StoreBackend.StoredSection purse = stored.sections().get("probe:purse");
        final int coins = purse == null ? 0 : Integer.parseInt(purse.json().replaceAll(".*\"coins\":(\\d+).*", "$1"));
        final int onTheGround = item.isRemoved() ? 0 : item.getItemStack().amount();
        System.out.println("written with the transfer: coins=" + coins + ", on the ground: " + onTheGround + ", " + seen);
        return new Walk(coins, onTheGround);
    }

    /** The freeze came first: the pickup was tried, every try ended cancelled and nothing was handed over. */
    private void assertNothingHandedOver(Walk walk) {
        assertTrue(seen.contains("global:monitor cancelled=true"), "the frozen player's pickup was tried: " + seen);
        assertEquals(AMOUNT, walk.written() + walk.onTheGround(), "diamonds after the walk in (" + walk + "): " + seen);
        assertEquals(Set.of("global:monitor cancelled=true"), Set.copyOf(seen), "what the listeners saw");
    }

    private Player aPlayerInTheWorld() {
        final Player player = StoreFixture.connect(env.process(), UUID.randomUUID(), "Copier");
        assertTrue(store.load(player), "loaded");
        store.admitted(player);
        StoreFixture.spawn(env.process(), player, instance);
        env.tick();
        assertInstanceOf(TickThread.class, player.acquirable().assignedThread(), "owned by a tick thread");
        return player;
    }

    /** One batch of packets: a move into the gate frame and what follows it; then the transfer goes out. */
    private void walkIntoTheGate(Player player, ClientPacket... after) {
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        player.addPacketToQueue(new ClientPlayerPositionPacket(ITEM_AT, true, false));
        for (ClientPacket packet : after) player.addPacketToQueue(packet);
        env.tick();
        await(env, () -> ledger.stream().anyMatch(line -> line.startsWith("transfer_issued ")), "the gate sent the player");
        env.tick();
    }

    /**
     * Completes the held back heartbeat read on a virtual thread, as {@code ShardHeartbeat} does, and waits for that
     * thread: the gate's transfer freezes the player on it, in the middle of the dispatch that calls this.
     */
    private void completeTheHeartbeatRead() {
        final CompletableFuture<List<HeartbeatRow>> held = Objects.requireNonNull(late);
        try {
            Thread.ofVirtual().start(() -> held.complete(List.of(OPEN))).join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /** Hands the stack over unless an earlier listener cancelled the pickup, into the purse of the picker. */
    private EventListener<PickupItemEvent> grant(String where, Runnable first) {
        return EventListener.builder(PickupItemEvent.class).ignoreCancelled(false).handler(event -> {
            if (event.isCancelled() || !(event.getLivingEntity() instanceof Player picker)) return;
            first.run();
            final int amount = event.getItemStack().amount();
            seen.add(where + " granted " + amount + " frozen=" + store.isFrozen(picker));
            store.get(picker, StoreFixture.PURSE).coins += amount;
            store.markDirty(picker, StoreFixture.PURSE);
        }).build();
    }
}
