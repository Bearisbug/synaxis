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
     * Registers a shutdown save hook; only allowed inside {@link SynaxisPlugin#onEnable}, and every
     * call adds one more. When the server shuts down, the host calls each of them once per online
     * player, on the tick thread that owns that player, before it saves everybody: this is where the
     * plugin writes what it keeps in memory into its sections and marks them dirty. A player that
     * was let in but has not entered the world yet has no such thread: when it leaves during the
     * save, the hook runs on the thread that reports its {@code PlayerDisconnectEvent}.
     * <p>
     * Two shutdowns call no hook. When the watchdog judged the tick stuck
     * ({@code docs/DESIGN-PROD.md} §11.4), the host saves the sections as they are. When the save
     * phase is over before a player's tick comes, nothing of that player is saved, its dirty
     * sections included: only the last periodic save holds. What lives only in the plugin's memory
     * is lost in both; written into a section and marked dirty as it changes, it is in the first
     * one's save and lost in the second only since the last periodic save.
     * <p>
     * A plugin must not save from {@code PlayerDisconnectEvent} on shutdown: by the time players
     * are kicked their data has already been written. A logout is the one disconnect whose save
     * comes after the plugins' listeners ({@link PlayerDataStore}).
     *
     * @param hook the hook; an exception it throws is logged against this plugin
     * @throws IllegalStateException if called outside {@code onEnable}
     */
    void onShutdownSave(Consumer<ShutdownNotice> hook);
}
