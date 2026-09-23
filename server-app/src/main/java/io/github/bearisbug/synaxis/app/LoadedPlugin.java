package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;

/**
 * What the host has to take back when a plugin leaves the {@code enabled} state (§11.2).
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
