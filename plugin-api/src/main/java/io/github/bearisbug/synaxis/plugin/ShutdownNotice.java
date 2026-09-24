package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a shutdown save hook is told about ({@link PluginLifecycle#onShutdownSave}).
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface ShutdownNotice {

    /**
     * The player whose data is about to be saved.
     *
     * @return the player
     */
    Player player();
}
