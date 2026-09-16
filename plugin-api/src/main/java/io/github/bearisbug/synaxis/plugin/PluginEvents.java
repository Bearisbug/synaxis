package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.event.Event;

import java.util.function.Consumer;

/**
 * Event capability of one plugin (API-M2-008).
 * <p>
 * Listeners are added to a host owned {@code EventNode.all("plugin/" + id)} child of the global
 * handler. The node itself stays with the host for two reasons: disabling the plugin is then a
 * single {@code removeChild}, and every listener can be wrapped so that an exception it throws is
 * logged against this plugin instead of surfacing at the root of the event tree with no identity.
 * <p>
 * A plugin must not attach nodes to the global handler itself. In particular nothing may build an
 * instance scoped mapped node for {@code PlayerPluginMessageEvent}: that event is also dispatched
 * during the configuration phase, where the player has no instance and the mapped branch asserts.
 */
public interface PluginEvents {

    /**
     * Adds a listener for one event type.
     *
     * @param eventType the event class to listen for
     * @param listener  the listener; an exception it throws is caught and logged against this plugin
     * @param <E>       the event type
     */
    <E extends Event> void addListener(Class<E> eventType, Consumer<E> listener);
}
