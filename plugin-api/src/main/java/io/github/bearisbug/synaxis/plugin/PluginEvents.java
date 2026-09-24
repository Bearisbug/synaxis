package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.instance.Instance;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Consumer;

/**
 * Event capability of one plugin (API-M2-008, API-PROD-018).
 * <p>
 * Listeners are added to host owned child nodes of the global handler, one per plugin and
 * {@link EventPriority}. The nodes stay with the host for two reasons: disabling the plugin detaches
 * all of them at once, and every listener can be wrapped so that an exception it throws is logged
 * against this plugin instead of surfacing at the root of the event tree with no identity.
 * <p>
 * Priorities order the listeners of every plugin together: all {@link EventPriority#LOWEST}
 * listeners of all plugins run before any {@link EventPriority#LOW} one, and so on up to
 * {@link EventPriority#MONITOR}. Within one priority, plugins run in the order each first
 * registered a listener at that priority, and one plugin's listeners in registration order.
 * <p>
 * A plugin must not attach nodes to the global handler itself. In particular nothing may build an
 * instance scoped mapped node for {@code PlayerPluginMessageEvent}: that event is also dispatched
 * during the configuration phase, where the player has no instance and the mapped branch asserts.
 * {@link #forInstance} refuses it for that reason.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginEvents {

    /**
     * The order in which listeners see an event, first to last.
     * <p>
     * A listener that wants the final say registers late ({@link #HIGH}, {@link #HIGHEST}); one
     * that only records the outcome registers at {@link #MONITOR}.
     */
    enum EventPriority {
        LOWEST,
        LOW,
        NORMAL,
        HIGH,
        HIGHEST,
        /**
         * Runs after every other priority and sees the final state of the event. A listener here
         * must not modify the event, cancelling included. This is a convention: engine events are
         * mutable objects with setters, so the host cannot enforce it.
         */
        MONITOR
    }

    /**
     * Adds a listener for one event type at {@link EventPriority#NORMAL} that also sees cancelled
     * events.
     *
     * @param eventType the event class to listen for
     * @param listener  the listener; an exception it throws is caught and logged against this plugin
     * @param <E>       the event type
     */
    default <E extends Event> void addListener(Class<E> eventType, Consumer<E> listener) {
        addListener(eventType, EventPriority.NORMAL, false, listener);
    }

    /**
     * Adds a listener for one event type.
     *
     * @param eventType       the event class to listen for
     * @param priority        when the listener runs relative to the others
     * @param ignoreCancelled true to skip the listener when an earlier one has cancelled the event;
     *                        no effect on events that cannot be cancelled
     * @param listener        the listener; an exception it throws is caught and logged against this
     *                        plugin
     * @param <E>             the event type
     */
    <E extends Event> void addListener(Class<E> eventType, EventPriority priority, boolean ignoreCancelled,
                                       Consumer<E> listener);

    /**
     * Returns the event capability scoped to one instance: its listeners are only called for events
     * of that instance, on the thread that ticks the instance.
     * <p>
     * The listeners hang below the instance's own event node. That node is dispatched before the
     * global scope listeners of every plugin, so priorities order the instance scoped listeners
     * among themselves, and all of them run before the global scope plugin listeners of the same
     * event. They are taken back when the plugin is disabled and go away with the instance. Every
     * call for the same instance shares that instance's priority nodes, so calling this once per
     * listener keeps the order within a priority.
     * <p>
     * Only {@code InstanceEvent} types can be listened to here. {@code PlayerPluginMessageEvent},
     * {@code PlayerCustomClickEvent}, {@code PlayerDisconnectEvent} and {@code PlayerPacketEvent} are
     * refused, and so are their supertypes such as {@code PlayerInstanceEvent}: all four can also be
     * dispatched while the player has no instance.
     *
     * @param instance the instance to scope to
     * @return the capability scoped to {@code instance}; its
     * {@link #addListener(Class, EventPriority, boolean, Consumer)} throws
     * {@link IllegalArgumentException} for an event type it cannot scope
     */
    PluginEvents forInstance(Instance instance);
}
