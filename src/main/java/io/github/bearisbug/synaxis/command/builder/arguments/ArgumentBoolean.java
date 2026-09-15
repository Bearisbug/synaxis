package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;

/**
 * Represents a boolean value.
 * <p>
 * Example: true
 */
public class ArgumentBoolean extends Argument<Boolean> {

    public static final int NOT_BOOLEAN_ERROR = 1;

    public ArgumentBoolean(String id) {
        super(id);
    }

    @Override
    public Boolean parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        if (input.equalsIgnoreCase("true"))
            return true;
        if (input.equalsIgnoreCase("false"))
            return false;

        throw new ArgumentSyntaxException("Not a boolean", input, NOT_BOOLEAN_ERROR);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.BOOL;
    }
    @Override
    public String toString() {
        return String.format("Boolean<%s>", getId());
    }
}
