package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.CommandSender;

/**
 * Functional interface used by the {@link io.github.bearisbug.synaxis.command.CommandManager}
 * to execute a callback if an unknown command is run.
 * You can set it with {@link io.github.bearisbug.synaxis.command.CommandManager#setUnknownCommandCallback(CommandCallback)}.
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
