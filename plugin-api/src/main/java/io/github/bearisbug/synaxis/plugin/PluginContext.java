package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * The capabilities the host grants to one plugin (API-M2-008).
 * <p>
 * Every capability is a host owned wrapper rather than an engine object, so that the host can take
 * all of them back in one move when the plugin is disabled and can name the plugin in the log line
 * of any exception the plugin throws.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginContext {

    /**
     * The engine process this plugin runs in.
     * <p>
     * This is the one capability that is not wrapped, and the only part of this interface that
     * carries no compatibility promise: a plugin reaching into the engine through it does so at its
     * own risk (see {@code docs/DESIGN-M2.md} §8).
     *
     * @return the running server process
     */
    ServerProcess process();

    /**
     * Event listeners of this plugin. They live on a host owned child node that is detached as a
     * whole when the plugin is disabled.
     *
     * @return the event capability of this plugin
     */
    PluginEvents events();

    /**
     * Scheduled tasks of this plugin. Every submitted task is remembered and cancelled when the
     * plugin is disabled.
     *
     * @return the scheduler capability of this plugin
     */
    PluginScheduler scheduler();

    /**
     * Custom payload channels of this plugin, all under its own {@code <plugin_id>:} namespace.
     *
     * @return the channel capability of this plugin
     */
    PluginChannels channels();

    /**
     * The admission provider slot, which decides what a connecting player is let into.
     *
     * @return the admission capability of this plugin
     */
    PluginAdmission admission();

    /**
     * {@code plugins/<plugin_id>/}, created by the host before {@link SynaxisPlugin#onEnable}.
     *
     * @return the data directory of this plugin
     */
    Path dataDirectory();

    /**
     * The analytics ledger the host writes, for the §20 events this plugin produces.
     *
     * @return the analytics capability of this plugin
     */
    PluginAnalytics analytics();

    /**
     * Player data of this plugin, stored by the host ({@code docs/DESIGN-PROD.md} API-PROD-005).
     * <p>
     * The host's context always has one. The default only exists so that a context a plugin wrote
     * for its own tests keeps compiling; such a test uses {@code InMemoryPlayerDataStore} of
     * {@code :testing} instead.
     *
     * @return the player data capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default PlayerDataStore playerData() {
        throw new UnsupportedOperationException("only the host's plugin context has a player store");
    }

    /**
     * Lifecycle hooks beyond enable and disable, such as the shutdown save
     * ({@code docs/DESIGN-PROD.md} API-PROD-007).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the lifecycle capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default PluginLifecycle lifecycle() {
        throw new UnsupportedOperationException("only the host's plugin context has lifecycle hooks");
    }

    /**
     * Permission nodes and roles of the players ({@code docs/DESIGN-PROD.md} API-PROD-010).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the permission capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default Permissions permissions() {
        throw new UnsupportedOperationException("only the host's plugin context has permissions");
    }

    /**
     * Bans and the whitelist ({@code docs/DESIGN-PROD.md} API-PROD-009).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the moderation capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default Moderation moderation() {
        throw new UnsupportedOperationException("only the host's plugin context has moderation");
    }

    /**
     * Commands of this plugin, all under its own {@code <plugin_id>:} namespace
     * ({@code docs/DESIGN-PROD.md} API-PROD-011).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the command capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default PluginCommands commands() {
        throw new UnsupportedOperationException("only the host's plugin context has commands");
    }

    /**
     * Who appears in whose tab list ({@code docs/DESIGN-PROD.md} API-PROD-020).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the tab list capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default TabList tabList() {
        throw new UnsupportedOperationException("only the host's plugin context has a tab list");
    }

    /**
     * Tracking ranges of the entity types ({@code docs/DESIGN-PROD.md} API-PROD-019).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the entity visibility capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default PluginEntities entities() {
        throw new UnsupportedOperationException("only the host's plugin context has entity settings");
    }

    /**
     * Vanilla transfers and the cookies of this plugin ({@code docs/DESIGN-PROD.md} API-PROD-021).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the transfer capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default Transfers transfers() {
        throw new UnsupportedOperationException("only the host's plugin context has transfers");
    }

    /**
     * Scenes, the HUD and actions of the players ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the play capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default PluginPlay play() {
        throw new UnsupportedOperationException("only the host's plugin context has play");
    }

    /**
     * Managed play instances, whose lifecycle the host runs ({@code docs/DESIGN-PLAY.md}
     * {@code API-PLAY-006}).
     * <p>
     * The host's context always has one; the default is there for the same reason as
     * {@link #playerData()}'s.
     *
     * @return the managed instance capability of this plugin
     * @throws UnsupportedOperationException from a context that is not the host's
     */
    default PluginInstances instances() {
        throw new UnsupportedOperationException("only the host's plugin context has instances");
    }

    /**
     * A logger named after this plugin.
     *
     * @return the logger of this plugin
     */
    Logger logger();
}
