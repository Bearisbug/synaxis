package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.jetbrains.annotations.Nullable;

public class ArgumentResourceOrTag extends Argument<String> {

    public static final int SPACE_ERROR = 1;

    private final String identifier;

    public ArgumentResourceOrTag(String id, String identifier) {
        super(id);
        this.identifier = identifier;
    }

    @Override
    public String parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        if (input.contains(StringUtils.SPACE))
            throw new ArgumentSyntaxException("Resource location cannot contain space character", input, SPACE_ERROR);

        return input;
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.RESOURCE_OR_TAG;
    }

    @Override
    public String toString() {
        return String.format("ResourceOrTag<%s>", getId());
    }

    @Override
    public byte @Nullable [] nodeProperties() {
        return NetworkBuffer.makeArray(NetworkBuffer.STRING, identifier);
    }
}
