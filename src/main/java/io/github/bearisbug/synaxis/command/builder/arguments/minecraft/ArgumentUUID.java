package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;

import java.util.UUID;

public class ArgumentUUID extends Argument<UUID> {

    public static final int INVALID_UUID = -1;

    public ArgumentUUID(String id) {
        super(id);
    }

    @Override
    public UUID parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException _) {
            throw new ArgumentSyntaxException("Invalid UUID", input, INVALID_UUID);
        }
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.UUID;
    }

    @Override
    public String toString() {
        return String.format("UUID<%s>", getId());
    }
}
