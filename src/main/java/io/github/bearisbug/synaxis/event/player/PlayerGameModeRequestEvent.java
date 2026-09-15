package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when a player uses the F3+F4 menu to try and change their gamemode.
 */
public class PlayerGameModeRequestEvent implements PlayerInstanceEvent {

    private final Player player;
    private final GameMode requestedGameMode;

    public PlayerGameModeRequestEvent(Player player, GameMode requestedGameMode) {
        this.player = player;
        this.requestedGameMode = requestedGameMode;
    }

    /**
     * Gets the requested gamemode.
     *
     * @return the requested gamemode
     */
    public GameMode getRequestedGameMode() {
        return requestedGameMode;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
