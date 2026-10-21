package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Represents an argument which will take all the remaining of the command, so it must be the last argument of a
 * syntax.
 * <p>
 * Example: Hey I am a string
 */
public class ArgumentStringArray extends Argument<String[]> {

    public ArgumentStringArray(String id) {
        super(id, true, true);
    }

    @Override
    public String[] parse(CommandSender sender, String input) {
        return input.split(Pattern.quote(StringUtils.SPACE));
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.STRING;
    }

    @Override
    public byte @Nullable [] nodeProperties() {
        return NetworkBuffer.makeArray(NetworkBuffer.VAR_INT, 2); // Greedy phrase
    }

    @Override
    public String toString() {
        return String.format("StringArray<%s>", getId());
    }
}
