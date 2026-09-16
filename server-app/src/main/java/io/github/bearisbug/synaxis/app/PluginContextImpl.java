package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.plugin.PluginAdmission;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginEvents;
import io.github.bearisbug.synaxis.plugin.PluginScheduler;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * The seven capabilities handed to one plugin (API-M2-008).
 *
 * @param process       the engine process
 * @param events        the event capability
 * @param scheduler     the scheduler capability
 * @param channels      the channel capability
 * @param admission     the admission capability
 * @param dataDirectory {@code plugins/<plugin_id>/}
 * @param logger        a logger named after the plugin
 */
record PluginContextImpl(ServerProcess process, PluginEvents events, PluginScheduler scheduler,
                         PluginChannels channels, PluginAdmission admission, Path dataDirectory,
                         Logger logger) implements PluginContext {
}
