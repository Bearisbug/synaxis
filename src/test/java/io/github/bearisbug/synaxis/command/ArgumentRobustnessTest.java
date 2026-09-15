package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CommandParserImpl only catches ArgumentSyntaxException, so anything else escapes to the packet listener as a bare
 * stack trace and the command fails with no feedback. All of these are reachable with one extra space.
 */
public class ArgumentRobustnessTest {

    @Test
    public void emptyTimeInput() {
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.Time("time").parse(null, ""));
    }

    @Test
    public void relativeVecWithAnEmptyComponent() {
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.RelativeVec3("vec").parse(null, "1  2"));
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.RelativeVec2("vec").parse(null, " 2"));
    }

    @Test
    public void relativeVecWithLocalAfterWorldCoordinates() {
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.RelativeVec3("vec").parse(null, "1 2 ^3"));
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.RelativeVec3("vec").parse(null, "~1 ~2 ^3"));
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.RelativeVec2("vec").parse(null, "-3 ^14.25"));
    }

    @Test
    public void registryArgumentsWithAnUnparseableKey() {
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.Entity("entity").parse(null, "@e[type=Pig]"));
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.EntityType("type").parse(null, "Pig"));
        assertThrows(ArgumentSyntaxException.class, () -> ArgumentType.Particle("particle").parse(null, "Flame"));
    }

    @Test
    public void stringWithAMalformedUnicodeEscape() {
        assertDoesNotThrow(() -> ArgumentType.String("string").parse(null, "\"\\uZZZZ\""));
        assertDoesNotThrow(() -> ArgumentType.String("string").parse(null, "\"\\u-123\""));
    }
}
