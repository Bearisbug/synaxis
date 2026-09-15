package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;

/**
 * Called after the player signals the server that his settings has been modified.
 */
public class PlayerSettingsChangeEvent implements PlayerEvent {

    private final Player player;

    public PlayerSettingsChangeEvent(Player player) {
        this.player = player;
    }

    /**
     * Gets the player who changed his settings.
     * <p>
     * You can retrieve the new player settings with {@link Player#getSettings()}.
     *
     * @return the player
     */
    @Override
    public Player getPlayer() {
        return player;
    }

}
