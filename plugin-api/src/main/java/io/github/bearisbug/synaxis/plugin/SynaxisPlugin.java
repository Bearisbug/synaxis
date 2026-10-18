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
     * Identity of this plugin. The host and the translation key gate call it any number of times,
     * the first ones before any capability exists: it must return an equal value every time and
     * have no side effect.
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
     * <p>
     * When this method throws, the host logs it, takes back everything registered so far (its
     * managed instances end with {@code EndCause.PLUGIN_DISABLED}) and goes on with the other
     * plugins. {@link #onDisable()} is not called: whatever the plugin opened itself before the throw
     * (a thread pool, a connection) has to be closed in a catch of this method.
     *
     * @param context the capabilities granted to this plugin
     */
    void onEnable(PluginContext context);

    /**
     * Called once when the host disables this plugin, in reverse enable order and before the engine
     * shuts its scheduler down; never for a plugin whose {@link #onEnable} threw.
     * <p>
     * It runs on a platform thread of its own, {@code plugin-disable-<id>}, that owns no tick thread:
     * an entity already assigned to a tick thread is changed through {@code acquirable().sync(...)},
     * one that is not yet assigned directly. The plugins share a budget of 5 s; a plugin still in
     * here when it runs out is left behind on its thread and its capabilities are taken back anyway.
     * <p>
     * The host takes back on its own the event nodes, the tasks, the channels, the admission
     * provider, the commands, the tab list visibility, the actions and the managed instances
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-007}); this method is only for whatever the plugin
     * owns itself.
     */
    default void onDisable() {
    }
}
