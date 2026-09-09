package dev.gameengine.command.builder.arguments;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.command.CommandSender;
import dev.gameengine.command.builder.exception.ArgumentSyntaxException;

public class ArgumentLiteral extends Argument<String> {

    public static final int INVALID_VALUE_ERROR = 1;

    public ArgumentLiteral(String id) {
        super(id);
    }

    @Override
    public String parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        if (!input.equals(getId()))
            throw new ArgumentSyntaxException("Invalid literal value", input, INVALID_VALUE_ERROR);

        return input;
    }

    @Override
    public ArgumentParserType parser() {
        return null;
    }

    @Override
    public String toString() {
        return String.format("Literal<%s>", getId());
    }
}
