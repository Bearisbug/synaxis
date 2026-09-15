package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when the gamemode of a player is being modified.
 */
public class PlayerGameModeChangeEvent implements PlayerInstanceEvent, CancellableEvent {

    private final Player player;
    private GameMode newGameMode;

    private boolean cancelled;

    public PlayerGameModeChangeEvent(Player player, GameMode newGameMode) {
        this.player = player;
        this.newGameMode = newGameMode;
    }

    /**
     * Gets the target gamemode.
     *
     * @return the target gamemode
     */
    public GameMode getNewGameMode() {
        return newGameMode;
    }

    /**
     * Changes the target gamemode.
     *
     * @param newGameMode the new target gamemode
     */
    public void setNewGameMode(GameMode newGameMode) {
        this.newGameMode = newGameMode;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
