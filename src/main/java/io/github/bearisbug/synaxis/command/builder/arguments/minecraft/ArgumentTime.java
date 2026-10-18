package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.temporal.TemporalUnit;

/**
 * Represents an argument giving a time (day/second/tick).
 * <p>
 * Example: 50d, 25s, 75t
 * <p>
 * {@code d} is a day of 24 hours, not the game day of 24,000 ticks the vanilla client counts it as.
 */
public class ArgumentTime extends Argument<Duration> {

    public static final int INVALID_TIME_FORMAT = -2;
    public static final int NO_NUMBER = -3;
    public static final int TOO_LOW = -4;

    private int min = 0;

    public ArgumentTime(String id) {
        super(id);
    }

    /**
     * Sets the shortest time accepted.
     *
     * @param min the minimum in ticks, 0 by default
     * @return 'this' for chaining
     * @throws IllegalArgumentException if {@code min} is negative
     */
    public ArgumentTime min(int min) {
        Check.argCondition(min < 0, "the minimum of a time is at least 0 ticks: " + min);
        this.min = min;
        return this;
    }

    @Override
    public Duration parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        if (input.isEmpty())
            throw new ArgumentSyntaxException("Time cannot be empty", input, NO_NUMBER);
        final char lastChar = input.charAt(input.length() - 1);

        final TemporalUnit timeUnit = switch (lastChar) {
            case 'd' -> TimeUnit.DAY;
            case 's' -> TimeUnit.SECOND;
            case 't' -> TimeUnit.SERVER_TICK;
            default -> {
                if (!Character.isDigit(lastChar))
                    throw new ArgumentSyntaxException("Time needs to have the unit d, s, t, or none", input,
                            INVALID_TIME_FORMAT);
                yield TimeUnit.SERVER_TICK;
            }
        };
        if (!Character.isDigit(lastChar)) input = input.substring(0, input.length() - 1);

        final Duration time;
        try {
            // Check if value is a number
            time = Duration.of(Integer.parseInt(input), timeUnit);
        } catch (NumberFormatException _) {
            throw new ArgumentSyntaxException("Time needs to be a number", input, NO_NUMBER);
        }
        if (time.compareTo(Duration.of(min, TimeUnit.SERVER_TICK)) < 0)
            throw new ArgumentSyntaxException("Time must be at least " + min + " ticks", input, TOO_LOW);
        return time;
    }

    @Override
    public byte @Nullable [] nodeProperties() {
        return NetworkBuffer.makeArray(NetworkBuffer.INT, min);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.TIME;
    }

    @Override
    public boolean sameConfiguration(Argument<?> other) {
        return other instanceof ArgumentTime time && min == time.min;
    }

    @Override
    public String toString() {
        return String.format("Time<%s>", getId());
    }
}
