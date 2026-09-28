package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.plugin.Moderation;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.plugin.PlayerDataStore;
import io.github.bearisbug.synaxis.plugin.PluginAdmission;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import io.github.bearisbug.synaxis.plugin.PluginCommands;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginEntities;
import io.github.bearisbug.synaxis.plugin.PluginEvents;
import io.github.bearisbug.synaxis.plugin.PluginLifecycle;
import io.github.bearisbug.synaxis.plugin.PluginScheduler;
import io.github.bearisbug.synaxis.plugin.TabList;
import io.github.bearisbug.synaxis.plugin.Transfers;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * The capabilities handed to one plugin (API-M2-008).
 *
 * @param process       the engine process
 * @param events        the event capability
 * @param scheduler     the scheduler capability
 * @param channels      the channel capability
 * @param admission     the admission capability
 * @param dataDirectory {@code plugins/<plugin_id>/}
 * @param analytics     the ledger the plugin writes its §20 events to
 * @param playerData    the plugin's view of the player store ({@code docs/DESIGN-PROD.md} API-PROD-005)
 * @param lifecycle     the plugin's shutdown save hook ({@code docs/DESIGN-PROD.md} API-PROD-007)
 * @param permissions   the permission nodes of the players ({@code docs/DESIGN-PROD.md} API-PROD-010)
 * @param moderation    the bans and the whitelist ({@code docs/DESIGN-PROD.md} API-PROD-009)
 * @param commands      the plugin's commands ({@code docs/DESIGN-PROD.md} API-PROD-011)
 * @param tabList       who appears in whose tab list ({@code docs/DESIGN-PROD.md} API-PROD-020)
 * @param entities      the tracking ranges of the entity types ({@code docs/DESIGN-PROD.md} API-PROD-019)
 * @param transfers     vanilla transfers and the plugin's cookies ({@code docs/DESIGN-PROD.md} API-PROD-021)
 * @param logger        a logger named after the plugin
 */
record PluginContextImpl(ServerProcess process, PluginEvents events, PluginScheduler scheduler,
                         PluginChannels channels, PluginAdmission admission, Path dataDirectory,
                         PluginAnalytics analytics, PlayerDataStore playerData, PluginLifecycle lifecycle,
                         Permissions permissions, Moderation moderation, PluginCommands commands, TabList tabList,
                         PluginEntities entities, Transfers transfers, Logger logger)
        implements PluginContext {
}
