package dev.gameengine.command.builder;

import dev.gameengine.command.CommandSender;
import dev.gameengine.command.builder.arguments.Argument;
import dev.gameengine.command.builder.exception.ArgumentSyntaxException;

/**
 * Callback executed when an error is found within the {@link Argument}.
 */
@FunctionalInterface
public interface ArgumentCallback {

    /**
     * Executed when an error is found.
     *
     * @param sender    the sender which executed the command
     * @param exception the exception containing the message, input and error code related to the issue
     */
    void apply(CommandSender sender, ArgumentSyntaxException exception);
}
