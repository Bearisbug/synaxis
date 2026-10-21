package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when a player uses the F3+F4 menu to try and change their gamemode.
 * <p>
 * A notification: not cancellable, and the engine does nothing with the request. The request is the client's to
 * send, a modified client sends it at any time whatever its permission level. A listener that grants it first checks
 * a permission node ({@code docs/DESIGN-PROD.md} {@code API-PROD-010}; not {@link Player#getPermissionLevel()}, see
 * {@code REQ-PROD-011}), then calls {@link Player#setGameMode(GameMode)} itself.
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
