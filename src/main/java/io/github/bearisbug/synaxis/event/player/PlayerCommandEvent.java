package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when {@link io.github.bearisbug.synaxis.command.CommandManager#execute} runs a command with a player as its
 * sender: what the player typed, and what a plugin executes as a player.
 */
public class PlayerCommandEvent implements PlayerInstanceEvent, CancellableEvent {

    private final Player player;
    private String command;

    private boolean cancelled;

    public PlayerCommandEvent(Player player, String command) {
        this.player = player;
        this.command = command;
    }

    /**
     * Gets the command used (command name + arguments), without the {@code /} prefix. The value the event is called
     * with has its leading and trailing whitespace removed; a value an earlier listener set with
     * {@link #setCommand(String)} is not trimmed again.
     *
     * @return the command that the player wants to execute
     */
    public String getCommand() {
        return command;
    }

    /**
     * Changes the command to execute, without the {@code /} prefix. The value is parsed as it is.
     *
     * @param command the new command
     */
    public void setCommand(String command) {
        this.command = command;
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
