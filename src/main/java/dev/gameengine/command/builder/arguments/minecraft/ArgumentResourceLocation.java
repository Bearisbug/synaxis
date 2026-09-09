package dev.gameengine.command.builder.arguments.minecraft;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.command.CommandSender;
import dev.gameengine.command.builder.arguments.Argument;
import dev.gameengine.command.builder.exception.ArgumentSyntaxException;

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
