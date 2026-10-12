package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.play.ActionHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The actions the plugins registered ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}): only in their
 * {@code onEnable}, each id once, and all of a plugin's taken back when it is torn down.
 */
final class ActionRegistry {

    /**
     * One registered action.
     *
     * @param pluginId the plugin it belongs to
     * @param handler  what the plugin does with it
     * @param failures the budget of this handler's failures, one per registration so that a handler
     *                 failing on every action cannot silence another one's first stack
     */
    record Registration(String pluginId, ActionHandler handler, LogBudget failures) {
    }

    private final Logger logger;
    private final Map<String, Registration> byId = new ConcurrentHashMap<>();
    private final Set<String> sealed = ConcurrentHashMap.newKeySet();

    /**
     * @param logger the logger the failures of the handlers are written to
     */
    ActionRegistry(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers one action of a plugin.
     *
     * @param pluginId the plugin registering
     * @param actionId the action id, with or without the plugin's prefix
     * @param handler  the handler
     * @return the full action id
     * @throws IllegalStateException    if the plugin's {@code onEnable} has returned, or the action is taken
     * @throws IllegalArgumentException if the id is not one of the plugin's
     */
    String register(String pluginId, String actionId, ActionHandler handler) {
        if (sealed.contains(pluginId))
            throw new IllegalStateException("plugin_id=" + pluginId + " registers its actions in onEnable only");
        final String id = PlayIds.qualify(pluginId, actionId);
        final Registration previous = byId.putIfAbsent(id,
                new Registration(pluginId, Objects.requireNonNull(handler, "handler"), new LogBudget(logger, "failures")));
        if (previous != null) throw new IllegalStateException("action " + id + " is already registered");
        return id;
    }

    /**
     * Closes the registration of a plugin, once its {@code onEnable} returned.
     *
     * @param pluginId the plugin
     */
    void seal(String pluginId) {
        sealed.add(pluginId);
    }

    /**
     * Drops every action of a plugin being torn down, and closes its registration.
     *
     * @param pluginId the plugin
     */
    void reclaim(String pluginId) {
        sealed.add(pluginId);
        byId.values().removeIf(registration -> registration.pluginId().equals(pluginId));
    }

    /**
     * The plugin and the handler of an action.
     *
     * @param actionId the full action id
     * @return its registration, null when no plugin has it
     */
    @Nullable Registration find(String actionId) {
        return byId.get(actionId);
    }
}
