package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;

/**
 * What the host has to take back when a plugin leaves the {@code enabled} state (§11.2).
 *
 * @param plugin    the plugin itself
 * @param id        its id, also the namespace of its channels
 * @param node      its event child node
 * @param scheduler its task wrapper
 */
record LoadedPlugin(SynaxisPlugin plugin, String id, EventNode<Event> node, PluginSchedulerImpl scheduler) {
}
