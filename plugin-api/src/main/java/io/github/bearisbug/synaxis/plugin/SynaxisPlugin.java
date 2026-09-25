package io.github.bearisbug.synaxis.plugin;

import java.util.Set;

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
     * The translate keys this plugin sends to players beyond the constants of {@link DisconnectKeys}:
     * its own disconnect reasons, admission rejections and messages, in any namespace. A client only
     * shows a sentence for a key the language files of the Synaxis client mod carry, so the
     * translation key gate of {@code docs/DESIGN-PROD.md} §24 ({@code API-PROD-029}) checks every key
     * named here against both of them. The host itself never calls this; the gate calls it on an
     * instance built the way the host builds one to read {@link #descriptor()}.
     *
     * @return the keys, none by default
     */
    default Set<String> translationKeys() {
        return Set.of();
    }

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
