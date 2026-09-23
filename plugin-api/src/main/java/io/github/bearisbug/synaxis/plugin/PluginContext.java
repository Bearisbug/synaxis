package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.ServerProcess;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * The capabilities the host grants to one plugin (API-M2-008).
 * <p>
 * Every capability is a host owned wrapper rather than an engine object, so that the host can take
 * all of them back in one move when the plugin is disabled and can name the plugin in the log line
 * of any exception the plugin throws.
 */
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
     * A logger named after this plugin.
     *
     * @return the logger of this plugin
     */
    Logger logger();
}
