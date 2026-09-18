package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.plugin.LogBudget;
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
 * <p>
 * That report goes through a {@link LogBudget} because a plugin listener can sit on an event a
 * client fires per packet, and a listener that throws on every one of them would otherwise write a
 * stack per packet ({@code docs/DESIGN.md} §15).
 *
 * @param pluginId the owning plugin
 * @param node     the host owned child node, detached as a whole when the plugin is disabled
 */
record PluginEventsImpl(String pluginId, EventNode<Event> node) implements PluginEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginEventsImpl.class);

    @Override
    public <E extends Event> void addListener(Class<E> eventType, Consumer<E> listener) {
        // One budget per listener: a plugin decides how many it registers, a client decides how
        // often each of them runs.
        final LogBudget failures = new LogBudget(LOGGER, "failures");
        node.addListener(eventType, event -> {
            try {
                listener.accept(event);
            } catch (Throwable throwable) {
                failures.error(throwable, "Plugin event listener failed: plugin_id={} event={}",
                        pluginId, eventType.getSimpleName());
            }
        });
    }
}
