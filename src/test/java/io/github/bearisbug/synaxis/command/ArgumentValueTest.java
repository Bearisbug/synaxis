package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentTime;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the arguments accept as values: a registry key that is a key, finite numbers, a time not below its minimum,
 * and an input a mapper or a predicate throws on reported as invalid.
 */
public class ArgumentValueTest {
    private static final ServerProcess PROCESS = ServerProcess.of();

    /**
     * The client builds a resource argument from the registry its key names: the key has to be one, and the input a
     * resource location (a tag, {@code #} then one, for ResourceOrTag).
     */
    @Test
    public void aResourceArgumentNeedsARegistryKeyAndAResourceLocation() {
        assertThrows(IllegalArgumentException.class, () -> ArgumentType.Resource("r", "Not A Key"));
        assertThrows(IllegalArgumentException.class, () -> ArgumentType.ResourceOrTag("r", "Not A Key"));

        var resource = ArgumentType.Resource("r", "minecraft:enchantment");
        assertArg(resource, "minecraft:sharpness", "minecraft:sharpness");
        assertInvalidArg(resource, "Foo:Bar");
        assertInvalidArg(resource, "!!!");
        assertInvalidArg(resource, "#minecraft:logs");

        var resourceOrTag = ArgumentType.ResourceOrTag("r", "minecraft:block");
        assertArg(resourceOrTag, "#minecraft:logs", "#minecraft:logs");
        assertArg(resourceOrTag, "minecraft:oak_log", "minecraft:oak_log");
        assertInvalidArg(resourceOrTag, "#Logs");
        assertInvalidArg(resourceOrTag, "!!!");
    }

    /**
     * Double.compare ranks NaN above every number, so a minimum alone let NaN and the infinities through; a
     * coordinate or a range bound read the same way.
     */
    @Test
    public void aNumberCoordinateOrRangeThatIsNotFiniteIsRefused() {
        var positive = ArgumentType.Double("d").min(0.0);
        for (String input : List.of("NaN", "Infinity", "1e400", "-Infinity"))
            assertInvalidArg(positive, input);
        assertArg(positive, 1000.0, "1e3");
        assertInvalidArg(ArgumentType.Float("f").min(0f), "NaN");
        assertInvalidArg(ArgumentType.Float("f"), "1e40");

        assertInvalidArg(ArgumentType.RelativeVec3("v"), "NaN 0 0");
        assertInvalidArg(ArgumentType.RelativeVec3("v"), "~Infinity ~ ~");
        assertInvalidArg(ArgumentType.RelativeVec2("v"), "^1 ^NaN");
        assertInvalidArg(ArgumentType.RelativeBlockPosition("v"), "~Infinity 0 0");
        assertInvalidArg(ArgumentType.FloatRange("r"), "NaN..1");
        assertInvalidArg(ArgumentType.FloatRange("r"), "0..Infinity");
    }

    /**
     * The minimum of a time argument holds, in ticks; a time is never negative.
     */
    @Test
    public void aTimeBelowItsMinimumIsRefused() {
        var time = ArgumentType.Time("t");
        assertInvalidArg(time, "-5");
        assertInvalidArg(time, "-1d");
        assertArg(time, Duration.ZERO, "0");

        var atLeastASecond = ArgumentType.Time("t").min(20);
        assertInvalidArg(atLeastASecond, "19t");
        assertInvalidArg(atLeastASecond, "0s");
        assertArg(atLeastASecond, Duration.of(20, TimeUnit.SERVER_TICK), "20");
        assertArg(atLeastASecond, Duration.of(1, TimeUnit.SECOND), "1s");
        assertArg(atLeastASecond, Duration.of(1, TimeUnit.DAY), "1d");

        assertThrows(IllegalArgumentException.class, () -> ArgumentType.Time("t").min(-1));
        final ArgumentSyntaxException unit = assertThrows(ArgumentSyntaxException.class,
                () -> time.parse(new ServerSender(PROCESS), "100x"));
        assertEquals(ArgumentTime.INVALID_TIME_FORMAT, unit.getErrorCode(), "a unit error is no number error");
    }

    /**
     * A mapper or a predicate that throws makes the input invalid, as returning null or false does; the parser only
     * catches what an argument reports as invalid.
     */
    @Test
    public void aMapperOrPredicateThatThrowsMakesTheInputInvalid() {
        final ArgumentSyntaxException mapped = assertThrows(ArgumentSyntaxException.class,
                () -> ArgumentType.Word("n").map(text -> Integer.parseInt(text)).parse(new ServerSender(PROCESS), "abc"));
        assertEquals(555, mapped.getErrorCode());
        assertInstanceOf(NumberFormatException.class, mapped.getCause());
        final ArgumentSyntaxException filtered = assertThrows(ArgumentSyntaxException.class,
                () -> ArgumentType.Word("w").filter(word -> word.charAt(3) == 'x').parse(new ServerSender(PROCESS), "ab"));
        assertEquals(556, filtered.getErrorCode());
        assertInstanceOf(StringIndexOutOfBoundsException.class, filtered.getCause());
        assertArg(ArgumentType.Word("n").map(text -> Integer.parseInt(text)), 12, "12");
    }

    @Test
    public void anEmptyGroupIsRefusedWhenItIsCreated() {
        assertThrows(IllegalArgumentException.class, () -> ArgumentType.Group("g"));
    }

    private static <T> void assertArg(Argument<T> arg, T expected, String input) {
        assertEquals(expected, arg.parse(new ServerSender(PROCESS), input));
    }

    private static <T> void assertInvalidArg(Argument<T> arg, String input) {
        assertThrows(ArgumentSyntaxException.class, () -> arg.parse(new ServerSender(PROCESS), input));
    }
}
