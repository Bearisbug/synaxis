package dev.gameengine.command.builder.arguments;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.command.CommandSender;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.utils.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Represents an argument which will take all the remaining of the command.
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
