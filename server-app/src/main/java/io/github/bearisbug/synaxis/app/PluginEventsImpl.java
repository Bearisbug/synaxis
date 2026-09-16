package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.plugin.PluginEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Event capability of one plugin (REQ-M2-004, REQ-M2-005).
 * <p>
 * Every listener is wrapped so that an exception it throws is logged against this plugin and goes
 * no further: an exception escaping into the event tree is caught at its root, where nothing knows
 * which plugin it came from and where the remaining listeners of that dispatch are skipped.
 *
 * @param pluginId the owning plugin
 * @param node     the host owned child node, detached as a whole when the plugin is disabled
 */
record PluginEventsImpl(String pluginId, EventNode<Event> node) implements PluginEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginEventsImpl.class);

    @Override
    public <E extends Event> void addListener(Class<E> eventType, Consumer<E> listener) {
        node.addListener(eventType, event -> {
            try {
                listener.accept(event);
            } catch (Throwable throwable) {
                LOGGER.error("Plugin event listener failed: plugin_id={} event={}",
                        pluginId, eventType.getSimpleName(), throwable);
            }
        });
    }
}
