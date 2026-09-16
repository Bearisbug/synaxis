package io.github.bearisbug.synaxis.plugin;

/**
 * A plugin, discovered by the host through {@link java.util.ServiceLoader} (API-M2-007).
 * <p>
 * Implementations must have a no argument constructor that registers nothing: the host builds the
 * instance only to read its {@link #descriptor()}, and every registration belongs in
 * {@link #onEnable(PluginContext)}. Hot reloading is not supported, changing a plugin means
 * restarting the process (ADR-M2-007).
 */
public interface SynaxisPlugin {

    /**
     * Identity of this plugin. Called once, right after construction, before any capability exists.
     *
     * @return the descriptor of this plugin
     */
    PluginDescriptor descriptor();

    /**
     * Called once when the host enables this plugin, on the starting thread and before the server
     * binds its port. Everything registered through {@code context} is taken back by the host when
     * the plugin is disabled.
     *
     * @param context the capabilities granted to this plugin
     */
    void onEnable(PluginContext context);

    /**
     * Called once when the host disables this plugin, in reverse enable order and before the engine
     * shuts its scheduler down. The host removes the event node, the tasks, the channels and the
     * admission provider on its own; this method is only for whatever the plugin owns itself.
     */
    default void onDisable() {
    }
}
