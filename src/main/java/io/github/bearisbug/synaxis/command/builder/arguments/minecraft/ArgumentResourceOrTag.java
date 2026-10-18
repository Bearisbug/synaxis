package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.utils.StringUtils;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a resource location or a tag ({@code #} then a resource location), completed by the client from one
 * of its registries.
 * <p>
 * The registry is named by its key, such as {@code minecraft:enchantment}. It has to be one the client knows,
 * built in or sent to it as registry data: a client that gets a command tree naming another registry disconnects.
 * For a registry only the server has, use {@link ArgumentResourceLocation} with a suggestion callback.
 */
public class ArgumentResourceOrTag extends Argument<String> {

    public static final int SPACE_ERROR = 1;
    public static final int INVALID_KEY_ERROR = 2;

    private final String registryKey;

    /**
     * @param id          the id of the argument
     * @param registryKey the key of the registry the client completes the input from
     * @throws IllegalArgumentException if {@code registryKey} is not a key
     */
    public ArgumentResourceOrTag(String id, String registryKey) {
        super(id);
        Check.argCondition(!Key.parseable(registryKey), "ArgumentResourceOrTag '" + id
                + "' needs a registry key such as minecraft:enchantment: " + registryKey);
        this.registryKey = registryKey;
    }

    @Override
    public String parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        if (input.contains(StringUtils.SPACE))
            throw new ArgumentSyntaxException("Resource location cannot contain space character", input, SPACE_ERROR);
        if (!Key.parseable(input.startsWith("#") ? input.substring(1) : input))
            throw new ArgumentSyntaxException("Invalid resource location", input, INVALID_KEY_ERROR);

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
        return NetworkBuffer.makeArray(NetworkBuffer.STRING, registryKey);
    }

    @Override
    public boolean sameConfiguration(Argument<?> other) {
        return other instanceof ArgumentResourceOrTag resource && registryKey.equals(resource.registryKey);
    }
}
