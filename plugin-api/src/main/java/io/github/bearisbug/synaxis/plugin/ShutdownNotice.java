package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;

/**
 * What a shutdown save hook is told about ({@link PluginLifecycle#onShutdownSave}).
 */
public interface ShutdownNotice {

    /**
     * The player whose data is about to be saved.
     *
     * @return the player
     */
    Player player();
}
