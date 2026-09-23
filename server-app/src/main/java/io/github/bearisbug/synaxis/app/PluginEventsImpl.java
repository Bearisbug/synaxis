package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.EventListener;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerCustomClickEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPacketEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.PluginEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Event capability of one plugin (REQ-M2-004, REQ-M2-005, REQ-PROD-022).
 * <p>
 * Every listener is wrapped so that an exception it throws is logged against this plugin and goes
 * no further: an exception escaping into the event tree is caught at its root, where nothing knows
 * which plugin it came from and where the remaining listeners of that dispatch are skipped.
 * <p>
 * That report goes through a {@link LogBudget} because a plugin listener can sit on an event a
 * client fires per packet, and a listener that throws on every one of them would otherwise write a
 * stack per packet ({@code docs/DESIGN.md} §15).
 * <p>
 * Each priority gets its own child node of the parent, created on first use with
 * {@link EventNode#setPriority}. The parent sorts its children by priority, so the nodes of every
 * plugin at one priority sit together and a single plugin wide node could not give that order.
 * The nodes are shared by every {@code PluginEventsImpl} of the plugin with the same parent, so
 * calling {@link #forInstance} once per listener still gives one node per priority and instance.
 * {@link #detach()} takes all of them back, including those below instance nodes.
 */
final class PluginEventsImpl implements PluginEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginEventsImpl.class);

    private final String pluginId;
    private final Scope scope;
    private final Registrations registrations;

    /**
     * @param pluginId the owning plugin
     * @param parent   the node the plugin wide priority nodes are attached to
     */
    PluginEventsImpl(String pluginId, EventNode<Event> parent) {
        this(pluginId, new GlobalScope(parent), new Registrations());
    }

    private PluginEventsImpl(String pluginId, Scope scope, Registrations registrations) {
        this.pluginId = pluginId;
        this.scope = scope;
        this.registrations = registrations;
    }

    @Override
    public <E extends Event> void addListener(Class<E> eventType, EventPriority priority, boolean ignoreCancelled,
                                              Consumer<E> listener) {
        scope.check(eventType);
        // One budget per listener: a plugin decides how many it registers, a client decides how
        // often each of them runs.
        final LogBudget failures = new LogBudget(LOGGER, "failures");
        final Consumer<E> wrapped = event -> {
            if (ignoreCancelled && event instanceof CancellableEvent cancellable && cancellable.isCancelled()) return;
            try {
                listener.accept(event);
            } catch (Throwable throwable) {
                failures.error(throwable, "Plugin event listener failed: plugin_id={} event={}",
                        pluginId, eventType.getSimpleName());
            }
        };
        synchronized (registrations) {
            // A listener added once the plugin is taken back would sit on a node nothing detaches
            if (registrations.detached) return;
            add(tier(priority), eventType, wrapped);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Event> void add(EventNode<?> node, Class<E> eventType, Consumer<E> listener) {
        // Built rather than EventNode#addListener(Class, Consumer), which skips a cancelled event
        // whenever eventType is cancellable: ignoreCancelled is decided by the wrapper alone.
        final EventListener<E> built = EventListener.builder(eventType).ignoreCancelled(false).handler(listener).build();
        // The scope has checked that eventType is below the node's event type
        ((EventNode<Event>) node).addListener((EventListener<Event>) (EventListener<?>) built);
    }

    private EventNode<?> tier(EventPriority priority) {
        assert Thread.holdsLock(registrations);
        final Map<EventPriority, WeakReference<EventNode<?>>> tiers =
                registrations.tiers.computeIfAbsent(scope.parent(), _ -> new EnumMap<>(EventPriority.class));
        final WeakReference<EventNode<?>> existing = tiers.get(priority);
        EventNode<?> node = existing != null ? existing.get() : null;
        if (node != null) return node;
        final String name = "plugin/" + pluginId + scope.suffix() + "/" + priority.name().toLowerCase(Locale.ROOT);
        // LOWEST..MONITOR as -2..3, so that NORMAL keeps the default priority 0 of other nodes
        node = scope.attach(name, priority.ordinal() - EventPriority.NORMAL.ordinal());
        tiers.put(priority, new WeakReference<>(node));
        return node;
    }

    @Override
    public PluginEvents forInstance(Instance instance) {
        return new PluginEventsImpl(pluginId, new InstanceScope(instance.eventNode(), instance.getUuid().toString()),
                registrations);
    }

    /**
     * Detaches every node this plugin's listeners hang on, in every scope. Listeners added afterwards
     * are dropped.
     */
    void detach() {
        synchronized (registrations) {
            registrations.detached = true;
            for (Map<EventPriority, WeakReference<EventNode<?>>> tiers : registrations.tiers.values()) {
                for (WeakReference<EventNode<?>> reference : tiers.values()) {
                    final EventNode<?> node = reference.get();
                    if (node != null) removeFromParent(node);
                }
            }
            registrations.tiers.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromParent(EventNode<?> node) {
        final EventNode<Event> parent = (EventNode<Event>) node.getParent();
        if (parent != null) parent.removeChild((EventNode<Event>) node);
    }

    /**
     * The priority nodes of one plugin across all its scopes, by the node they are attached to.
     * Held weakly: the parent keeps a node alive as long as it is attached, and an instance node
     * together with its children goes away with the instance, which a plugin creating an instance
     * per dungeon run relies on. The values are weak too, since a priority node refers to its
     * parent and a strong one would keep the key alive.
     */
    private static final class Registrations {
        private final Map<EventNode<?>, Map<EventPriority, WeakReference<EventNode<?>>>> tiers = new WeakHashMap<>();
        private boolean detached;
    }

    private sealed interface Scope {
        EventNode<?> parent();

        void check(Class<? extends Event> eventType);

        String suffix();

        EventNode<?> attach(String name, int priority);
    }

    private record GlobalScope(EventNode<Event> parent) implements Scope {
        @Override
        public void check(Class<? extends Event> eventType) {
        }

        @Override
        public String suffix() {
            return "";
        }

        @Override
        public EventNode<?> attach(String name, int priority) {
            final EventNode<Event> node = EventNode.all(name).setPriority(priority);
            parent.addChild(node);
            return node;
        }
    }

    private record InstanceScope(EventNode<InstanceEvent> parent, String instanceId) implements Scope {
        /**
         * Instance events that are also fired for a player without an instance: by
         * {@code PlayerConnection} and the configuration listeners, and for {@code PlayerPacketEvent}
         * by the PLAY packets drained in the same batch as the one that ends the configuration.
         */
        private static final List<Class<? extends Event>> DISPATCHED_WITHOUT_INSTANCE = List.of(
                PlayerPluginMessageEvent.class, PlayerCustomClickEvent.class, PlayerDisconnectEvent.class,
                PlayerPacketEvent.class);

        @Override
        public void check(Class<? extends Event> eventType) {
            if (!InstanceEvent.class.isAssignableFrom(eventType))
                throw new IllegalArgumentException("Not an instance event, it cannot be scoped to an instance: "
                        + eventType.getName());
            if (dispatchedWithoutInstance(eventType))
                throw new IllegalArgumentException("Also dispatched while the player has no instance: "
                        + eventType.getName());
        }

        private static boolean dispatchedWithoutInstance(Class<? extends Event> eventType) {
            // A supertype of these would route them into the mapped branch just the same
            for (Class<? extends Event> type : DISPATCHED_WITHOUT_INSTANCE) {
                if (eventType.isAssignableFrom(type) || type.isAssignableFrom(eventType)) return true;
            }
            return false;
        }

        @Override
        public String suffix() {
            return "@" + instanceId;
        }

        @Override
        public EventNode<?> attach(String name, int priority) {
            final EventNode<InstanceEvent> node = EventNode.type(name, EventFilter.INSTANCE).setPriority(priority);
            parent.addChild(node);
            return node;
        }
    }
}
