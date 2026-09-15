package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;

/**
 * Represents a resource location (namespaced identifier) value.
 * <p>
 *     Example: {@code minecraft:air}
 * </p>
 */
public class ArgumentResourceLocation extends Argument<Key> {

    public static final int PARSE_ERROR = 1;

    public ArgumentResourceLocation(String id) {
        super(id);
    }

    @Override
    public Key parse(CommandSender sender, @KeyPattern String input) throws ArgumentSyntaxException {
        if (!Key.parseable(input))
            throw new ArgumentSyntaxException("Invalid resource location", input, PARSE_ERROR);

        return Key.key(input);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.RESOURCE_LOCATION;
    }

    @Override
    public String toString() {
        return String.format("ResourceLocation<%s>", getId());
    }
}
