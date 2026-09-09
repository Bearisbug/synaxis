package dev.gameengine.utils.callback;

import dev.gameengine.command.CommandSender;

/**
 * Functional interface used by the {@link dev.gameengine.command.CommandManager}
 * to execute a callback if an unknown command is run.
 * You can set it with {@link dev.gameengine.command.CommandManager#setUnknownCommandCallback(CommandCallback)}.
 */
@FunctionalInterface
public interface CommandCallback {

    /**
     * Executed if an unknown command is run.
     *
     * @param sender  the command sender
     * @param command the complete command string
     */
    void apply(CommandSender sender, String command);

}
