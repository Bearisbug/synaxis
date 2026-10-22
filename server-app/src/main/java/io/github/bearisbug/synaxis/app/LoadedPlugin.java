package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;

/**
 * The capabilities a plugin holds handles of its own, which the host closes one by one when it leaves
 * the {@code enabled} state (§11.2). The capabilities kept in host wide registries (channels,
 * admission, tab list, actions, managed instances) are taken back by id in {@code PluginHost#tearDown},
 * and the player data only when {@code onEnable} fails, by {@code PluginHost#enableOne}.
 *
 * @param plugin    the plugin itself
 * @param id        its id, also the namespace of its channels
 * @param events    its event capability, which owns its event nodes
 * @param scheduler its task wrapper
 * @param commands  its commands
 */
record LoadedPlugin(SynaxisPlugin plugin, String id, PluginEventsImpl events, PluginSchedulerImpl scheduler,
                    PluginCommandsImpl commands) {
}
