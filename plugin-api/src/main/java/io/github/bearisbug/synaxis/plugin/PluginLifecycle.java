package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.ApiStatus;

import java.util.function.Consumer;

/**
 * Lifecycle hooks of one plugin beyond enable and disable ({@code docs/DESIGN-PROD.md}
 * API-PROD-007).
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginLifecycle {

    /**
     * Registers the shutdown save hook. When the server shuts down, the host calls it once per
     * online player, on the tick thread that owns that player, before it saves everybody: this is
     * where the plugin writes what it keeps in memory into its sections and marks them dirty. A
     * player that was let in but has not entered the world yet has no such thread: when it leaves
     * during the save, the hook runs on the thread that reports its {@code PlayerDisconnectEvent}.
     * <p>
     * A plugin must not save from {@code PlayerDisconnectEvent} on shutdown: by the time players
     * are kicked their data has already been written.
     *
     * @param hook the hook; an exception it throws is logged against this plugin
     */
    void onShutdownSave(Consumer<ShutdownNotice> hook);
}
