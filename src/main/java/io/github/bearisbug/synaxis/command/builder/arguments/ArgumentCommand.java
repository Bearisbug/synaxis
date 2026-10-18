package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.CommandDispatcher;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.jetbrains.annotations.ApiStatus;

public class ArgumentCommand extends Argument<CommandResult> {

    public static final int INVALID_COMMAND_ERROR = 1;

    private boolean onlyCorrect;
    private String shortcut = "";

    public ArgumentCommand(String id) {
        super(id, true, true);
    }

    @Override
    public CommandResult parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        final String commandString = !shortcut.isEmpty() ?
                shortcut + StringUtils.SPACE + input
                : input;
        CommandDispatcher dispatcher = sender.process().command().getDispatcher();
        CommandResult result = dispatcher.parse(sender, commandString);

        if (onlyCorrect && result.getType() != CommandResult.Type.SUCCESS)
            throw new ArgumentSyntaxException("Invalid command", input, INVALID_COMMAND_ERROR);

        return result;
    }

    @Override
    public ArgumentParserType parser() {
        return null;
    }

    public boolean isOnlyCorrect() {
        return onlyCorrect;
    }

    public ArgumentCommand setOnlyCorrect(boolean onlyCorrect) {
        this.onlyCorrect = onlyCorrect;
        return this;
    }

    public String getShortcut() {
        return shortcut;
    }

    /**
     * Sets the command the client completes this argument as, by redirecting its node there.
     *
     * @param shortcut the start of a registered command, such as {@code tp}; when it parses to no command, or to one
     *                 hidden from a player, that player's client is redirected to the root instead
     * @return 'this' for chaining
     */
    @ApiStatus.Experimental
    public ArgumentCommand setShortcut(String shortcut) {
        this.shortcut = shortcut;
        return this;
    }

    @Override
    public String toString() {
        return String.format("Command<%s>", getId());
    }
}
