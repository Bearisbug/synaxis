package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.entity.EntityTickEvent;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.event.player.PlayerCustomClickEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPacketEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.event.server.ServerTickMonitorEvent;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
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
import io.github.bearisbug.synaxis.thread.TickThread;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event priorities, {@code ignoreCancelled} and the instance scope of REQ-PROD-022
 * (API-PROD-018), through the plugin host so that the nodes sit where production puts them.
 */
@EnvTest
class PluginEventsIntegrationTest {

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;

    /** Stops the ledger's writer before JUnit deletes {@link #workingDirectory}. */
    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    private static final class ProbeEvent implements CancellableEvent {
        private final List<String> calls;
        private boolean cancelled;

        ProbeEvent(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            this.cancelled = cancel;
            calls.add("cancelled=" + cancel);
        }
    }

    private record Plugin(String id, Consumer<PluginEvents> onEnable) implements SynaxisPlugin {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public void onEnable(PluginContext ctx) {
            onEnable.accept(ctx.events());
        }
    }

    private PluginHost host(Env env, SynaxisPlugin... plugins) {
        return host(env, null, plugins);
    }

    /** The same with the host's player store chosen by the test, an in-memory one when null. */
    private PluginHost host(Env env, @Nullable PlayerStore store, SynaxisPlugin... plugins) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PluginHost host = store == null
                ? new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger)
                : new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
        host.enable(List.of(plugins), Set.of());
        return host;
    }

    @Test
    void thePrioritiesOfEveryPluginRunInOneOrder(Env env) {
        final List<String> calls = new CopyOnWriteArrayList<>();
        // Registered out of order and spread over two plugins, the first one enabled holding the
        // late priorities: a node per plugin would run all of "first" before any of "second".
        final Plugin first = new Plugin("first", events -> {
            events.addListener(ProbeEvent.class, EventPriority.MONITOR, false, _ -> calls.add("first:monitor"));
            events.addListener(ProbeEvent.class, EventPriority.HIGH, false, _ -> calls.add("first:high"));
            events.addListener(ProbeEvent.class, EventPriority.LOWEST, false, _ -> calls.add("first:lowest"));
        });
        final Plugin second = new Plugin("second", events -> {
            events.addListener(ProbeEvent.class, EventPriority.HIGHEST, false, _ -> calls.add("second:highest"));
            events.addListener(ProbeEvent.class, _ -> calls.add("second:normal"));
            events.addListener(ProbeEvent.class, EventPriority.LOW, false, _ -> calls.add("second:low"));
            events.addListener(ProbeEvent.class, EventPriority.HIGH, false, _ -> calls.add("second:high"));
        });
        final PluginHost host = host(env, first, second);

        env.process().eventHandler().call(new ProbeEvent(calls));

        assertEquals(List.of("first:lowest", "second:low", "second:normal", "first:high", "second:high",
                "second:highest", "first:monitor"), calls);
        host.disableAll();
    }

    @Test
    void aCancelledEventSkipsTheListenersThatIgnoreCancelled(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Plugin moderation = new Plugin("moderation", events ->
                events.addListener(PlayerChatEvent.class, EventPriority.LOW, false, event -> {
                    calls.add("low:cancel");
                    event.setCancelled(true);
                }));
        final Plugin guilds = new Plugin("guilds", events -> {
            events.addListener(PlayerChatEvent.class, EventPriority.NORMAL, true, _ -> calls.add("normal:ignoring"));
            // The two argument form is NORMAL and still sees a cancelled event
            events.addListener(PlayerChatEvent.class, _ -> calls.add("normal:default"));
            events.addListener(PlayerChatEvent.class, EventPriority.MONITOR, false,
                    event -> calls.add("monitor:cancelled=" + event.isCancelled()));
        });
        final PluginHost host = host(env, moderation, guilds);

        env.process().eventHandler().call(new PlayerChatEvent(player, List.of(), "hello"));

        assertEquals(List.of("low:cancel", "normal:default", "monitor:cancelled=true"), calls);
        host.disableAll();
    }

    @Test
    void aListenerThatIgnoresCancelledStillRunsBeforeALaterCancel(Env env) {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Plugin late = new Plugin("late", events ->
                events.addListener(ProbeEvent.class, EventPriority.HIGH, false, event -> {
                    calls.add("high:cancel");
                    event.setCancelled(true);
                }));
        final Plugin early = new Plugin("early", events ->
                events.addListener(ProbeEvent.class, EventPriority.NORMAL, true, _ -> calls.add("normal:ignoring")));
        final PluginHost host = host(env, late, early);

        env.process().eventHandler().call(new ProbeEvent(calls));

        assertEquals(List.of("normal:ignoring", "high:cancel", "cancelled=true"), calls);
        host.disableAll();
    }

    @Test
    void anInstanceScopedListenerOnlyHearsItsInstanceOnItsTickThread(Env env) {
        final Instance a = env.createFlatInstance();
        final Instance b = env.createFlatInstance();
        final Entity inA = new Entity(env.process(), EntityType.ZOMBIE);
        inA.setInstance(a, new Pos(0, 41, 0)).join();
        final Entity inB = new Entity(env.process(), EntityType.ZOMBIE);
        inB.setInstance(b, new Pos(0, 41, 0)).join();
        final List<Entity> heard = new CopyOnWriteArrayList<>();
        final List<String> offThread = new CopyOnWriteArrayList<>();
        final Plugin scoped = new Plugin("scoped", events ->
                events.forInstance(a).addListener(EntityTickEvent.class, EventPriority.NORMAL, false, event -> {
                    heard.add(event.getEntity());
                    final Thread current = Thread.currentThread();
                    if (!(current instanceof TickThread) || event.getEntity().acquirable().assignedThread() != current)
                        offThread.add(current.getName());
                }));
        final PluginHost host = host(env, scoped);

        env.tick();
        env.tick();

        assertFalse(heard.isEmpty(), "the entity in A ticked twice");
        assertTrue(heard.stream().allMatch(entity -> entity == inA), "heard an entity outside A: " + heard);
        assertEquals(List.of(), offThread);
        host.disableAll();
    }

    @Test
    void theInstanceScopeRefusesWhatItCannotScope(Env env) {
        final Instance instance = env.createFlatInstance();
        final PluginEvents[] kept = new PluginEvents[1];
        final PluginHost host = host(env, new Plugin("scoped", events -> kept[0] = events));
        final PluginEvents scope = kept[0].forInstance(instance);

        for (Class<? extends Event> type : List.of(ServerTickMonitorEvent.class, PlayerPluginMessageEvent.class,
                PlayerCustomClickEvent.class, PlayerDisconnectEvent.class, PlayerPacketEvent.class,
                PlayerInstanceEvent.class)) {
            assertThrows(IllegalArgumentException.class, () -> scope.addListener(type, _ -> {
            }), type.getName());
        }
        // A supertype is refused for the type it covers, which the message names with the way out
        final IllegalArgumentException covering = assertThrows(IllegalArgumentException.class,
                () -> scope.addListener(PlayerInstanceEvent.class, _ -> {
                }));
        assertTrue(covering.getMessage().contains("it covers " + PlayerPluginMessageEvent.class.getName()),
                covering.getMessage());
        assertTrue(covering.getMessage().contains("listen on the global scope"), covering.getMessage());
        final IllegalArgumentException itself = assertThrows(IllegalArgumentException.class,
                () -> scope.addListener(PlayerDisconnectEvent.class, _ -> {
                }));
        assertFalse(itself.getMessage().contains("it covers"), itself.getMessage());
        assertTrue(itself.getMessage().contains("listen on the global scope"), itself.getMessage());
        scope.addListener(PlayerChatEvent.class, _ -> {
        });
        assertEquals(1, instance.eventNode().getChildren().size());
        host.disableAll();
    }

    @Test
    void theGlobalScopeRefusesATypeNoEventIsDispatchedAs(Env env) {
        final PluginEvents[] kept = new PluginEvents[1];
        final PluginHost host = host(env, new Plugin("global", events -> kept[0] = events));
        final EventNode<?> hostNode = env.process().eventHandler().getChildren().stream()
                .filter(node -> node.getName().equals("synaxis-host")).findFirst().orElseThrow();

        // The engine dispatches on the exact runtime class: these would never be heard
        for (Class<? extends Event> type : List.of(PlayerEvent.class, PlayerInstanceEvent.class, CancellableEvent.class)) {
            assertThrows(IllegalArgumentException.class, () -> kept[0].addListener(type, EventPriority.HIGH, false, _ -> {
            }), type.getName());
        }
        assertEquals(0, pluginNodes(hostNode), "refused before a priority node is built");
        host.disableAll();
    }

    @Test
    void callingForInstanceAgainSharesTheInstanceNodes(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<String> calls = new CopyOnWriteArrayList<>();
        final PluginEvents[] kept = new PluginEvents[2];
        final PluginHost host = host(env, new Plugin("p1", events -> kept[0] = events),
                new Plugin("p2", events -> kept[1] = events));

        // One forInstance call per listener, the second plugin registering between the first's two
        kept[0].forInstance(instance).addListener(PlayerChatEvent.class, _ -> calls.add("p1:a"));
        kept[1].forInstance(instance).addListener(PlayerChatEvent.class, _ -> calls.add("p2:c"));
        kept[0].forInstance(instance).addListener(PlayerChatEvent.class, _ -> calls.add("p1:b"));
        env.process().eventHandler().call(new PlayerChatEvent(player, List.of(), "hello"));

        assertEquals(List.of("p1:a", "p1:b", "p2:c"), calls);
        assertEquals(2, instance.eventNode().getChildren().size(), "one NORMAL node per plugin");
        host.disableAll();
        assertEquals(0, instance.eventNode().getChildren().size());
    }

    @Test
    void disablingTakesBackEveryPriorityAndEveryInstanceScope(Env env) {
        final Instance instance = env.createFlatInstance();
        final Entity entity = new Entity(env.process(), EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 41, 0)).join();
        final AtomicInteger global = new AtomicInteger();
        final AtomicInteger scoped = new AtomicInteger();
        final PluginEvents[] kept = new PluginEvents[1];
        final Plugin plugin = new Plugin("plugin", events -> {
            kept[0] = events;
            for (EventPriority priority : EventPriority.values())
                events.addListener(ProbeEvent.class, priority, false, _ -> global.incrementAndGet());
            events.forInstance(instance).addListener(EntityTickEvent.class, EventPriority.HIGH, false,
                    _ -> scoped.incrementAndGet());
        });
        final PluginHost host = host(env, plugin);
        final EventNode<?> hostNode = env.process().eventHandler().getChildren().stream()
                .filter(node -> node.getName().equals("synaxis-host")).findFirst().orElseThrow();
        assertEquals(EventPriority.values().length, pluginNodes(hostNode));
        assertEquals(1, instance.eventNode().getChildren().size());

        env.process().eventHandler().call(new ProbeEvent(new CopyOnWriteArrayList<>()));
        env.tick();
        assertEquals(EventPriority.values().length, global.get());
        assertEquals(1, scoped.get());

        host.disableAll();
        // A listener the plugin adds from a task that outlived it goes nowhere
        kept[0].addListener(ProbeEvent.class, _ -> global.incrementAndGet());
        kept[0].forInstance(instance).addListener(EntityTickEvent.class, _ -> scoped.incrementAndGet());

        assertEquals(0, pluginNodes(hostNode));
        assertEquals(0, instance.eventNode().getChildren().size());
        env.process().eventHandler().call(new ProbeEvent(new CopyOnWriteArrayList<>()));
        env.tick();
        assertEquals(EventPriority.values().length, global.get());
        assertEquals(1, scoped.get());
    }

    @Test
    void theInstanceScopeRunsBeforeEveryGlobalPluginListener(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<String> calls = new CopyOnWriteArrayList<>();
        // The earliest global priority against the latest instance one, from the plugin enabled first
        final Plugin global = new Plugin("global", events ->
                events.addListener(PlayerChatEvent.class, EventPriority.LOWEST, false, _ -> calls.add("global:lowest")));
        final Plugin scoped = new Plugin("scoped", events ->
                events.forInstance(instance).addListener(PlayerChatEvent.class, EventPriority.MONITOR, false,
                        _ -> calls.add("instance:monitor")));
        final PluginHost host = host(env, global, scoped);

        env.process().eventHandler().call(new PlayerChatEvent(player, List.of(), "hello"));

        assertEquals(List.of("instance:monitor", "global:lowest"), calls);
        host.disableAll();
    }

    @Test
    void theStoreFreezeOverridesHighestAndMonitorSeesItsOutcome(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Plugin plugin = new Plugin("plugin", events -> {
            events.addListener(PickupItemEvent.class, EventPriority.HIGHEST, false, event -> event.setCancelled(false));
            events.addListener(ItemDropEvent.class, EventPriority.HIGHEST, false, event -> event.setCancelled(false));
            events.addListener(PickupItemEvent.class, EventPriority.MONITOR, false,
                    event -> calls.add("pickup:cancelled=" + event.isCancelled()));
            events.addListener(ItemDropEvent.class, EventPriority.MONITOR, false,
                    event -> calls.add("drop:cancelled=" + event.isCancelled()));
        });
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        final PluginHost host = host(env, store, plugin);
        // The shutdown save phase freezes every player, the only freeze a store without a session has
        store.saveAll(Duration.ofSeconds(1), true).join();

        final PickupItemEvent pickup = new PickupItemEvent(player, new ItemEntity(ItemStack.of(Material.DIAMOND)));
        env.process().eventHandler().call(pickup);
        final ItemDropEvent drop = new ItemDropEvent(player, ItemStack.of(Material.DIAMOND));
        env.process().eventHandler().call(drop);

        assertEquals(List.of("pickup:cancelled=true", "drop:cancelled=true"), calls);
        assertTrue(pickup.isCancelled() && drop.isCancelled(), "the un-cancel of HIGHEST does not win");
        host.disableAll();
    }

    /** A plugin's own kind of disconnect: an instance scope refuses it as the type it extends. */
    static final class OwnDisconnectEvent extends PlayerDisconnectEvent {
        OwnDisconnectEvent(Player player) {
            super(player);
        }
    }

    @Test
    void theInstanceScopeRefusesASubtypeAsTheTypeItExtends(Env env) {
        final Instance instance = env.createFlatInstance();
        final PluginEvents[] kept = new PluginEvents[1];
        final PluginHost host = host(env, new Plugin("scoped", events -> kept[0] = events));

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> kept[0].forInstance(instance).addListener(OwnDisconnectEvent.class, _ -> {
                }));
        assertTrue(refused.getMessage().contains("it is a " + PlayerDisconnectEvent.class.getName() + ", which"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("listen on the global scope"), refused.getMessage());
        host.disableAll();
    }

    /** The plugins' nodes under the host node, which also holds the host's own (the store's freeze). */
    private static long pluginNodes(EventNode<?> hostNode) {
        return hostNode.getChildren().stream().filter(node -> node.getName().startsWith("plugin/")).count();
    }
}
