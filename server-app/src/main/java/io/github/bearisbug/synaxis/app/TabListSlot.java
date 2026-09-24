package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * The one tab list visibility of the process ({@code docs/DESIGN-PROD.md} API-PROD-020), held by
 * the plugin that set it and taken back when that plugin is disabled.
 * <p>
 * The engine calls the visibility whenever it sends an entry, which clients joining and a plugin's
 * {@code setListed} calls decide the number of, so what it throws is counted towards a line every
 * 10 s rather than logged each time, and the entry is sent listed.
 */
final class TabListSlot {

    private static final Logger LOGGER = LoggerFactory.getLogger(TabListSlot.class);

    private final ConnectionManager connections;
    private final LogBudget failures = new LogBudget(LOGGER, "tab list visibility failures");
    private @Nullable String owner;

    TabListSlot(ConnectionManager connections) {
        this.connections = connections;
    }

    synchronized void set(String pluginId, BiPredicate<Player, Player> visibility) {
        Objects.requireNonNull(visibility, "visibility");
        final String current = this.owner;
        if (current != null && !current.equals(pluginId))
            throw new IllegalStateException("The process has one tab list visibility: plugin_id=" + pluginId
                    + " tried to set it, plugin_id=" + current + " holds it");
        this.owner = pluginId;
        connections.setTabListVisibility((viewer, subject) -> {
            try {
                return visibility.test(viewer, subject);
            } catch (RuntimeException e) {
                failures.warn(e, "Tab list visibility of plugin_id={} threw, {} is listed for {}",
                        pluginId, subject.getUsername(), viewer.getUsername());
                return true;
            }
        });
    }

    /**
     * Drops the visibility if the plugin being disabled set it; everyone is listed again.
     *
     * @param pluginId the plugin being disabled
     */
    synchronized void unregister(String pluginId) {
        if (!pluginId.equals(owner)) return;
        this.owner = null;
        connections.setTabListVisibility(null);
    }
}
