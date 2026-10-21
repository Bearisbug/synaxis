package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.jetbrains.annotations.Nullable;

/**
 * Argument which will take a quoted string.
 * <p>
 * Example: "Hey I am a string"
 */
public class ArgumentString extends Argument<String> {

    private static final char BACKSLASH = '\\';
    private static final char DOUBLE_QUOTE = '"';
    private static final char QUOTE = '\'';

    public static final int QUOTE_ERROR = 1;

    public ArgumentString(String id) {
        super(id, true);
    }

    @Override
    public String parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        return staticParse(input);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.STRING;
    }

    @Override
    public byte @Nullable [] nodeProperties() {
        return NetworkBuffer.makeArray(NetworkBuffer.VAR_INT, 1); // Quotable phrase
    }

    /**
     * @deprecated use {@link Argument#parse(CommandSender, Argument)}
     */
    @Deprecated
    public static String staticParse(String input) throws ArgumentSyntaxException {
        // Return if not quoted
        if (!input.contains(String.valueOf(DOUBLE_QUOTE)) &&
                !input.contains(String.valueOf(QUOTE)) &&
                !input.contains(StringUtils.SPACE)) {
            return input;
        }

        // Check if value start and end with quote
        final char first = input.charAt(0);
        final char last = input.charAt(input.length() - 1);
        final boolean quote = input.length() >= 2 &&
                first == last && (first == DOUBLE_QUOTE || first == QUOTE);
        if (!quote)
            throw new ArgumentSyntaxException("String argument needs to start and end with quotes", input, QUOTE_ERROR);

        // Remove first and last characters (quotes)
        input = input.substring(1, input.length() - 1);

        // Quotes of the opening kind inside must be escaped, the closing quote must not be
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == BACKSLASH) {
                escaped = true;
            } else if (c == first) {
                throw new ArgumentSyntaxException("Non-escaped quote", input, QUOTE_ERROR);
            }
        }
        if (escaped) throw new ArgumentSyntaxException("Escaped closing quote", input, QUOTE_ERROR);

        return StringUtils.unescapeJavaString(input);
    }

    @Override
    public String toString() {
        return String.format("String<%s>", getId());
    }
}
