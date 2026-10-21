package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentGroup;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentLoop;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an argument refuses while it is set up, which would otherwise only show once a player runs the command, or
 * never: a node left out of the command tree, a word that can never match, a range no input fits.
 */
public class ArgumentSetupTest {
    private static final ServerProcess PROCESS = ServerProcess.of();

    @Test
    public void aBoundThatIsNullIsRefusedWhereItIsSet() {
        assertThrows(NullPointerException.class, () -> ArgumentType.Integer("i").min(null));
        assertThrows(NullPointerException.class, () -> ArgumentType.Integer("i").max(null));
        assertThrows(NullPointerException.class, () -> ArgumentType.Integer("i").between(null, 1));
        assertThrows(NullPointerException.class, () -> ArgumentType.Long("l").between(1L, null));
    }

    @Test
    public void anEmptyLoopAWordThatIsNoWordAndARangeUpsideDownAreRefused() {
        var emptyLoop = assertThrows(IllegalArgumentException.class, () -> ArgumentType.Loop("l"));
        assertTrue(emptyLoop.getMessage().contains("'l'"), emptyLoop.getMessage());

        var spaced = assertThrows(IllegalArgumentException.class,
                () -> ArgumentType.Word("w").from("add", "a b"));
        assertTrue(spaced.getMessage().contains("'w'"), spaced.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ArgumentType.Word("w").from(""));

        var upsideDown = assertThrows(IllegalArgumentException.class,
                () -> ArgumentType.Integer("n").between(10, 1));
        assertTrue(upsideDown.getMessage().contains("'n'"), upsideDown.getMessage());

        // The bounds may meet, and words without a space are what a restriction is for
        var one = ArgumentType.Integer("n").between(1, 1);
        assertEquals(1, one.parse(new ServerSender(PROCESS), "1"));
        assertThrows(ArgumentSyntaxException.class, () -> one.parse(new ServerSender(PROCESS), "2"));
        assertEquals("remove", ArgumentType.Word("w").from("add", "remove").parse(new ServerSender(PROCESS), "remove"));
    }

    @Test
    public void theArgumentsOfALoopOrAGroupDoNotChangeAfterwards() {
        ArgumentLoop<String> loop = ArgumentType.Loop("l", ArgumentType.Word("a"), ArgumentType.Word("b"));
        assertThrows(UnsupportedOperationException.class, () -> loop.arguments().add(ArgumentType.Word("c")));
        assertEquals(List.of("a", "b"), loop.arguments().stream().map(Argument::getId).toList());

        Argument<?>[] members = {ArgumentType.Integer("first"), ArgumentType.Integer("second")};
        ArgumentGroup group = ArgumentType.Group("g", members);
        members[1] = ArgumentType.Word("swapped");
        assertEquals(List.of("first", "second"), group.group().stream().map(Argument::getId).toList());
        assertEquals(2, group.parse(new ServerSender(PROCESS), "1 2").<Integer>get("second"));
    }
}
