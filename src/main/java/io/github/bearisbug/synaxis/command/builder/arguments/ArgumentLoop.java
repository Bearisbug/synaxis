package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.utils.StringUtils;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Repeats its arguments until the input runs out, so it takes the rest of the input and must be the last argument of
 * a syntax.
 *
 * @param <T> the type of each item of the loop
 */
public class ArgumentLoop<T> extends Argument<List<T>> {

    public static final int INVALID_INPUT_ERROR = 1;

    private final List<Argument<T>> arguments;

    /**
     * @param id        the id of the argument
     * @param arguments the arguments each item of the loop is one of
     * @throws IllegalArgumentException if {@code arguments} is empty
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public ArgumentLoop(String id, Argument<T>... arguments) {
        super(id, true, true);
        Check.argCondition(arguments.length == 0, "ArgumentLoop '" + id + "' needs at least one argument");
        this.arguments = List.of(arguments);
    }

    @Override
    public List<T> parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        List<T> result = new ArrayList<>();
        final String[] split = input.split(StringUtils.SPACE, 0);

        final StringBuilder builder = new StringBuilder();
        boolean success = false;
        for (String s : split) {
            builder.append(s);

            for (Argument<T> argument : arguments) {
                try {
                    final String inputString = builder.toString();
                    final T value = argument.parse(sender, inputString);
                    success = true;
                    result.add(value);
                    break;
                } catch (ArgumentSyntaxException _) {
                    success = false;
                }
            }
            if (success) {
                builder.setLength(0); // Clear
            } else {
                builder.append(StringUtils.SPACE);
            }
        }

        if (result.isEmpty() || !success) {
            throw new ArgumentSyntaxException("Invalid loop, there is no valid argument found", input, INVALID_INPUT_ERROR);
        }

        return result;
    }

    /**
     * Gets the arguments each item of the loop is one of.
     *
     * @return the arguments, unmodifiable
     */
    public List<Argument<T>> arguments() {
        return arguments;
    }

    @Override
    public @Nullable ArgumentParserType parser() {
        return null;
    }
}
