package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.play.DefaultPlay;
import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.PluginEvents.EventPriority;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default play layer of {@code docs/DESIGN-PROD.md} {@code API-PROD-031} on the plugin host's own node, the way
 * {@code Main} attaches it in step (5): which mode gets which features, a plugin that changes what the host decided,
 * and the store's freeze.
 */
@EnvTest
class DefaultPlayHostIntegrationTest {

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;

    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    private record Plugin(String id, Consumer<PluginContext> onEnable) implements SynaxisPlugin {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public void onEnable(PluginContext ctx) {
            onEnable.accept(ctx);
        }
    }

    /**
     * Every feature in the core world, the chat alone under a gameplay plugin, and nothing on a map shard, whose play
     * rules are left to the gameplay design ({@code ADR-PROD-017}), nor in a refused launch.
     */
    @Test
    void eachModeGetsItsPlayLayer() {
        assertEquals(EnumSet.allOf(Feature.class), Main.defaultPlay(CoreMode.Mode.CORE, false), "the core world");
        assertEquals(EnumSet.of(Feature.CHAT), Main.defaultPlay(CoreMode.Mode.GAMEPLAY, false), "a gameplay plugin");
        assertEquals(Set.of(), Main.defaultPlay(CoreMode.Mode.CORE, true), "a map shard");
        assertEquals(Set.of(), Main.defaultPlay(CoreMode.Mode.REFUSED, false), "a refused launch");
    }

    /**
     * A plugin's global scope listeners run after the host's: one at {@code NORMAL} empties the chat recipients the
     * host filled in, one at {@code HIGHEST} cancels the drop the host would have spawned an item entity for.
     */
    @Test
    void aGlobalPluginListenerOverridesTheDefaults(Env env) {
        final Plugin plugin = new Plugin("override", ctx -> {
            ctx.events().addListener(PlayerChatEvent.class, EventPriority.NORMAL, true,
                    event -> event.getRecipients().clear());
            ctx.events().addListener(ItemDropEvent.class, EventPriority.HIGHEST, true, event -> event.setCancelled(true));
        });
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        final PluginHost host = host(env, store, plugin);
        final InstanceContainer world = coreWorld(env, host);
        final TestConnection connectionA = env.createConnection();
        final TestConnection connectionB = env.createConnection();
        final Player a = connectionA.connect(world, CoreMode.SPAWN);
        connectionB.connect(world, CoreMode.SPAWN);
        final Collector<SystemChatPacket> toA = connectionA.trackIncoming(SystemChatPacket.class);
        final Collector<SystemChatPacket> toB = connectionB.trackIncoming(SystemChatPacket.class);

        say(a, "hello");
        dropOne(a);

        toA.assertEmpty();
        toB.assertEmpty();
        assertEquals(ItemStack.of(Material.STONE, 64), a.getInventory().getItemStack(0), "the starter kit, kept");
        assertEquals(List.of(), items(world), "item entities after a drop a plugin cancelled");
        host.disableAll();
    }

    /**
     * An instance scoped plugin listener runs before the host node: the recipient it named is the only one.
     */
    @Test
    void anInstancePluginListenerChoosesTheRecipientsFirst(Env env) {
        final AtomicReference<PluginContext> context = new AtomicReference<>();
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        final PluginHost host = host(env, store, new Plugin("whisper", context::set));
        final InstanceContainer world = coreWorld(env, host);
        final TestConnection connectionA = env.createConnection();
        final TestConnection connectionB = env.createConnection();
        final Player a = connectionA.connect(world, CoreMode.SPAWN);
        final Player b = connectionB.connect(world, CoreMode.SPAWN);
        final Collector<SystemChatPacket> toA = connectionA.trackIncoming(SystemChatPacket.class);
        final Collector<SystemChatPacket> toB = connectionB.trackIncoming(SystemChatPacket.class);
        final PluginContext ctx = context.get();
        assertNotNull(ctx, "the plugin was enabled");
        ctx.events().forInstance(world).addListener(PlayerChatEvent.class, EventPriority.NORMAL, true,
                event -> event.getRecipients().add(b));

        say(a, "psst");

        toA.assertEmpty();
        toB.assertSingle();
        host.disableAll();
    }

    /**
     * A frozen player's drop reaches the host cancelled by the root node ({@code API-PROD-005}): no item entity, and
     * the stack stays in the hand.
     */
    @Test
    void aFrozenPlayerDropsNothing(Env env) {
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        final PluginHost host = host(env, store);
        final InstanceContainer world = coreWorld(env, host);
        final Player player = env.createPlayer(world, CoreMode.SPAWN);
        // The shutdown save phase freezes every player; with the tick alive the snapshot runs on the player's tick
        final CompletableFuture<?> saved = store.saveAll(Duration.ofSeconds(1), true);
        assertTrue(env.tickWhile(() -> !saved.isDone(), Duration.ofSeconds(5)), "the save phase did not end");

        dropOne(player);

        assertEquals(ItemStack.of(Material.STONE, 64), player.getInventory().getItemStack(0), "the starter kit, kept");
        assertEquals(List.of(), items(world), "item entities after a frozen player's drop");
        host.disableAll();
    }

    /**
     * Until {@code synaxis:player} is registered, a character counts as new while its inventory is empty.
     */
    @Test
    void aCharacterIsNewWhileItsInventoryIsEmpty(Env env) {
        final Player player = env.createPlayer(env.createFlatInstance(), CoreMode.SPAWN);
        assertTrue(Main.emptyInventory(player), "an empty inventory");
        player.getInventory().setItemStack(PlayerInventoryUtils.OFFHAND_SLOT, ItemStack.of(Material.DIRT));
        assertFalse(Main.emptyInventory(player), "a stack in the off hand");
    }

    private PluginHost host(Env env, PlayerStore store, Plugin... plugins) {
        final EventLedger opened = new EventLedger(workingDirectory.resolve("logs"), "test",
                EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        opened.start();
        this.ledger = opened;
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), opened, store);
        host.enable(List.<SynaxisPlugin>of(plugins), Set.of());
        return host;
    }

    /**
     * Step (5) of {@code Main} for the core world: the world, then the layer on the host node.
     */
    private static InstanceContainer coreWorld(Env env, PluginHost host) {
        final InstanceContainer world = CoreMode.start(env.process(), host, Main.registerMatchDimensionType(env.process()),
                CoreMode.Setting.ON);
        DefaultPlay.attach(env.process(), host.hostNode(), Main.defaultPlay(CoreMode.Mode.CORE, false),
                Main::emptyInventory, world);
        return world;
    }

    private static void say(Player player, String message) {
        player.addPacketToQueue(new ClientChatMessagePacket(message, System.currentTimeMillis(), 0L, null, 0,
                new BitSet(), (byte) 0));
        player.interpretPacketQueue();
    }

    private static void dropOne(Player player) {
        player.addPacketToQueue(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.DROP_ITEM, Vec.ZERO,
                BlockFace.BOTTOM, 0));
        player.interpretPacketQueue();
    }

    private static List<ItemEntity> items(InstanceContainer world) {
        return world.getEntities().stream().filter(ItemEntity.class::isInstance).map(ItemEntity.class::cast).toList();
    }
}
