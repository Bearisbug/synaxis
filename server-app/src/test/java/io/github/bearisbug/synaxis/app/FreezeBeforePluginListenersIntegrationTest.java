package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventListener;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.PluginEvents;
import io.github.bearisbug.synaxis.plugin.PluginEvents.EventPriority;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static io.github.bearisbug.synaxis.testing.TestUtils.waitUntilCleared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frozen player's pickup and drop reach every plugin listener already cancelled, in every scope
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-005}). A listener that honours cancels and runs before the freeze would
 * hand the stack over, and the freeze would then cancel the pickup: the item stays on the ground and the next save
 * takes the copy.
 * <p>
 * One plugin listens at every priority in the global scope and in the instance scope, on the player's own node
 * (the entity scope, reached through the engine) and on the root node itself, all of them with
 * {@code ignoreCancelled} false, and records whether the event was cancelled when it got there.
 */
@EnvTest
class FreezeBeforePluginListenersIntegrationTest {

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

    @Test
    void everyPluginListenerSeesAFrozenPickupAndDropCancelled(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<String> seen = new CopyOnWriteArrayList<>();
        final List<String> where = new ArrayList<>(List.of("root", "entity"));
        final Plugin plugin = new Plugin("grant", ctx -> {
            for (EventPriority priority : EventPriority.values()) {
                final String name = priority.name().toLowerCase(Locale.ROOT);
                listen(ctx.events(), "global:" + name, priority, seen);
                listen(ctx.events().forInstance(instance), "instance:" + name, priority, seen);
                where.add("global:" + name);
                where.add("instance:" + name);
            }
            // Through the engine rather than PluginEvents: both are dispatched before the host node
            player.eventNode().addListener(pickup("entity", seen)).addListener(drop("entity", seen));
            ctx.process().eventHandler().addListener(pickup("root", seen)).addListener(drop("root", seen));
        });
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
        host.enable(List.of(plugin), Set.of());

        pickUpAndDrop(env, player);
        assertEquals(expected(where, false), seen.stream().sorted().toList(), "every listener, before the freeze");

        seen.clear();
        // The shutdown save phase freezes every player, as a transfer or a degraded session freezes one
        store.saveAll(Duration.ofSeconds(1), true).join();
        assertTrue(store.forPlugin("grant").isFrozen(player), "frozen");
        final boolean cancelled = pickUpAndDrop(env, player);

        assertEquals(List.of(), seen.stream().filter(line -> line.endsWith("=false")).sorted().toList(),
                "listeners that saw a frozen pickup or drop allowed");
        assertEquals(expected(where, true), seen.stream().sorted().toList(), "every listener, frozen");
        assertTrue(cancelled, "the pickup and the drop end cancelled");
        host.disableAll();
    }

    /**
     * The freeze node cancels again the very events the root node cancelled: a drop that a listener dispatches while
     * it has a frozen player's pickup does not take the pickup's place. A plugin un-cancels both at {@code HIGHEST},
     * and its {@code MONITOR} listeners must still see both cancelled.
     */
    @Test
    void aDropDispatchedInsideAFrozenPickupLeavesBothCancelled(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<String> seen = new CopyOnWriteArrayList<>();
        final Plugin plugin = new Plugin("nested", ctx -> {
            final PluginEvents events = ctx.events();
            events.addListener(PickupItemEvent.class, EventPriority.NORMAL, false,
                    _ -> ctx.process().eventHandler().call(new ItemDropEvent(player, ItemStack.of(Material.DIAMOND))));
            events.addListener(PickupItemEvent.class, EventPriority.HIGHEST, false, event -> event.setCancelled(false));
            events.addListener(ItemDropEvent.class, EventPriority.HIGHEST, false, event -> event.setCancelled(false));
            events.addListener(PickupItemEvent.class, EventPriority.MONITOR, false,
                    event -> seen.add("pickup cancelled=" + event.isCancelled()));
            events.addListener(ItemDropEvent.class, EventPriority.MONITOR, false,
                    event -> seen.add("drop cancelled=" + event.isCancelled()));
        });
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
        host.enable(List.of(plugin), Set.of());
        store.saveAll(Duration.ofSeconds(1), true).join();

        final PickupItemEvent pickup = new PickupItemEvent(player, new ItemEntity(ItemStack.of(Material.DIAMOND)));
        env.process().eventHandler().call(pickup);

        assertEquals(List.of("drop cancelled=true", "pickup cancelled=true"), seen, "what the MONITOR listeners saw");
        assertTrue(pickup.isCancelled(), "the pickup ends cancelled");
        host.disableAll();
    }

    /**
     * The root node decides on a pickup or drop that reaches it already cancelled too: a plugin un-cancels both at
     * {@code HIGHEST}, and its {@code MONITOR} listeners must still see both cancelled, whatever cancelled them before.
     */
    @Test
    void aPickupAndDropCancelledBeforeTheRootAreCancelledAgainAfterAnUndo(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<String> seen = new CopyOnWriteArrayList<>();
        final Plugin plugin = new Plugin("undo", ctx -> {
            final PluginEvents events = ctx.events();
            events.addListener(PickupItemEvent.class, EventPriority.HIGHEST, false, event -> event.setCancelled(false));
            events.addListener(ItemDropEvent.class, EventPriority.HIGHEST, false, event -> event.setCancelled(false));
            events.addListener(PickupItemEvent.class, EventPriority.MONITOR, false,
                    event -> seen.add("pickup cancelled=" + event.isCancelled()));
            events.addListener(ItemDropEvent.class, EventPriority.MONITOR, false,
                    event -> seen.add("drop cancelled=" + event.isCancelled()));
        });
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
        host.enable(List.of(plugin), Set.of());
        store.saveAll(Duration.ofSeconds(1), true).join();

        final PickupItemEvent pickup = new PickupItemEvent(player, new ItemEntity(ItemStack.of(Material.DIAMOND)));
        pickup.setCancelled(true);
        env.process().eventHandler().call(pickup);
        final ItemDropEvent drop = new ItemDropEvent(player, ItemStack.of(Material.DIAMOND));
        drop.setCancelled(true);
        env.process().eventHandler().call(drop);

        assertEquals(List.of("pickup cancelled=true", "drop cancelled=true"), seen, "what the MONITOR listeners saw");
        host.disableAll();
    }

    /**
     * A dispatch cut short between the two cancels keeps nothing alive: a listener put straight on the root node,
     * outside the plugin wrapper, throws after the store's listener cancelled a frozen player's pickup. The root node's
     * handle catches it and skips the rest of the dispatch, the freeze node included, so the pickup never leaves the
     * store's set that way; the set must not keep it, with its player and item, reachable from this thread.
     */
    @Test
    void aPickupCutShortBeforeTheFreezeNodeIsNotKeptAlive(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        final Plugin plugin = new Plugin("thrower", ctx -> ctx.process().eventHandler().addListener(
                EventListener.builder(PickupItemEvent.class).ignoreCancelled(false).handler(_ -> {
                    throw new IllegalStateException("thrown between the two cancels");
                }).build()));
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
        host.enable(List.of(plugin), Set.of());
        store.saveAll(Duration.ofSeconds(1), true).join();

        final WeakReference<PickupItemEvent> cutShort = pickUpAndLetGo(env, player);

        assertEquals(1, reported.size(), "the dispatch was cut short: " + reported);
        waitUntilCleared(cutShort);
        host.disableAll();
    }

    /** Dispatches a pickup and a drop of the player, and tells whether both ended cancelled. */
    private static boolean pickUpAndDrop(Env env, Player player) {
        final PickupItemEvent pickup = new PickupItemEvent(player, new ItemEntity(ItemStack.of(Material.DIAMOND)));
        env.process().eventHandler().call(pickup);
        final ItemDropEvent drop = new ItemDropEvent(player, ItemStack.of(Material.DIAMOND));
        env.process().eventHandler().call(drop);
        return pickup.isCancelled() && drop.isCancelled();
    }

    /** Dispatches a pickup of the player, checks that it ended cancelled, and keeps only a weak reference to it. */
    private static WeakReference<PickupItemEvent> pickUpAndLetGo(Env env, Player player) {
        final PickupItemEvent pickup = new PickupItemEvent(player, new ItemEntity(ItemStack.of(Material.DIAMOND)));
        env.process().eventHandler().call(pickup);
        assertTrue(pickup.isCancelled(), "the frozen player's pickup ends cancelled");
        return new WeakReference<>(pickup);
    }

    private static void listen(PluginEvents events, String where, EventPriority priority, List<String> seen) {
        events.addListener(PickupItemEvent.class, priority, false,
                event -> seen.add(where + ":pickup cancelled=" + event.isCancelled()));
        events.addListener(ItemDropEvent.class, priority, false,
                event -> seen.add(where + ":drop cancelled=" + event.isCancelled()));
    }

    private static EventListener<PickupItemEvent> pickup(String where, List<String> seen) {
        return EventListener.builder(PickupItemEvent.class).ignoreCancelled(false)
                .handler(event -> seen.add(where + ":pickup cancelled=" + event.isCancelled())).build();
    }

    private static EventListener<ItemDropEvent> drop(String where, List<String> seen) {
        return EventListener.builder(ItemDropEvent.class).ignoreCancelled(false)
                .handler(event -> seen.add(where + ":drop cancelled=" + event.isCancelled())).build();
    }

    private static List<String> expected(List<String> where, boolean cancelled) {
        return where.stream()
                .flatMap(at -> List.of(at + ":pickup cancelled=" + cancelled, at + ":drop cancelled=" + cancelled).stream())
                .sorted().toList();
    }
}
