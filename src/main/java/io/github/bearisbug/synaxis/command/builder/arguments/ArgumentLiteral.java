package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;

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
