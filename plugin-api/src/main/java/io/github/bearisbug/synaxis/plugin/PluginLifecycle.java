package io.github.bearisbug.synaxis.plugin;

import java.util.function.Consumer;

/**
 * Lifecycle hooks of one plugin beyond enable and disable ({@code docs/DESIGN-PROD.md}
 * API-PROD-007).
 */
public interface PluginLifecycle {

    /**
     * Registers the shutdown save hook. When the server shuts down, the host calls it once per
     * online player, on the tick thread that owns that player, before it saves everybody: this is
     * where the plugin writes what it keeps in memory into its sections and marks them dirty.
     * <p>
     * A plugin must not save from {@code PlayerDisconnectEvent} on shutdown: by the time players
     * are kicked their data has already been written.
     *
     * @param hook the hook; an exception it throws is logged against this plugin
     */
    void onShutdownSave(Consumer<ShutdownNotice> hook);
}
