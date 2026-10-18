package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandDispatcher;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType.Literal;
import static io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType.Word;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing at the end of the input, where nothing is left to read, and past an argument whose mapper throws.
 */
public class CommandParseInputEndTest {
    private static final ServerProcess PROCESS = ServerProcess.of();

    /**
     * An argument that reads to the end of the input leaves nothing for its children: whichever child comes first,
     * none reads back the last character of what the argument consumed.
     */
    @Test
    public void nothingIsLeftAfterAnArgumentThatReadsTheRest() {
        for (boolean bFirst : new boolean[]{true, false}) {
            final AtomicBoolean b = new AtomicBoolean();
            final AtomicBoolean c = new AtomicBoolean();
            final Graph.Builder rest = Graph.builder(Literal("foo"));
            rest.append(ArgumentType.StringArray("a"), x -> {
                if (bFirst) x.append(Word("b"), createExecutor(b)).append(Word("c"), createExecutor(c));
                else x.append(Word("c"), createExecutor(c)).append(Word("b"), createExecutor(b));
            });
            assertSyntaxError(Graph.merge(rest.build()), "foo x y");
        }
    }

    /**
     * A sibling that fails at the end of the input, then an argument taking its default value, leaves the cursor at
     * the end: the next child cannot read back the last character of the word before.
     */
    @Test
    public void aDefaultValueAtTheEndOfTheInputLeavesNothingToRead() {
        final AtomicBoolean w = new AtomicBoolean();
        final AtomicBoolean x = new AtomicBoolean();
        var foo = Graph.merge(Graph.builder(Literal("foo"))
                .append(Word("a"), a -> a
                        .append(Literal("z"))
                        .append(ArgumentType.Integer("n").setDefaultValue(5), n -> n
                                .append(Literal("x"), createExecutor(x))
                                .append(Word("w"), createExecutor(w))))
                .build());
        assertSyntaxError(foo, "foo hello");
        assertValid(foo, "foo hello 7 word", w);
    }

    /**
     * Parsing input that ends with a space, as the parser is given it without trimming, reads no empty word.
     */
    @Test
    public void aTrailingSpaceIsNoEmptyWord() {
        final AtomicBoolean l = new AtomicBoolean();
        final AtomicBoolean b = new AtomicBoolean();
        var foo = Graph.merge(Graph.builder(Literal("foo"))
                .append(Word("a").setDefaultValue("d"), a -> a
                        .append(Literal("l"), createExecutor(l))
                        .append(Word("b"), createExecutor(b)))
                .build());
        assertSyntaxError(foo, "foo ");
        assertValid(foo, "foo q r", b);
    }

    /**
     * A mapper that throws on the input makes the argument fail like any invalid input: the next syntax is tried,
     * and an input no syntax takes is a syntax error.
     */
    @Test
    public void aMapperThatThrowsLetsTheNextSyntaxTryTheInput() {
        final List<Integer> paid = new CopyOnWriteArrayList<>();
        final AtomicInteger all = new AtomicInteger();
        final Command pay = new Command("pay");
        pay.addSyntax((_, context) -> paid.add(context.get("amount")), Word("amount").map(text -> Integer.parseInt(text)));
        pay.addSyntax((_, _) -> all.incrementAndGet(), Word("all").from("all"));
        final CommandDispatcher dispatcher = new CommandDispatcher(PROCESS);
        dispatcher.register(pay);
        final ServerSender sender = new ServerSender(PROCESS);

        assertEquals(CommandResult.Type.SUCCESS, dispatcher.execute(sender, "pay all").getType());
        assertEquals(1, all.get());
        assertEquals(CommandResult.Type.INVALID_SYNTAX, dispatcher.execute(sender, "pay abc").getType());
        assertEquals(CommandResult.Type.SUCCESS, dispatcher.execute(sender, "pay 5").getType());
        assertEquals(List.of(5), paid);
    }

    private static void assertSyntaxError(Graph graph, String input) {
        assertInstanceOf(CommandParser.Result.KnownCommand.Invalid.class, parseCommand(graph, input));
    }

    private static void assertValid(Graph graph, String input, AtomicBoolean executorTest) {
        final CommandParser.Result result = parseCommand(graph, input);
        assertInstanceOf(CommandParser.Result.KnownCommand.Valid.class, result);
        result.executable().execute(null);
        assertTrue(executorTest.get(), "Parser returned valid syntax, but with the wrong executor.");
        executorTest.set(false);
    }

    private static CommandParser.Result parseCommand(Graph graph, String input) {
        return CommandParser.parser().parse(new ServerSender(PROCESS), graph, input);
    }

    private static Graph.Execution createExecutor(AtomicBoolean atomicBoolean) {
        return new GraphImpl.ExecutionImpl(null, null, null, (_, _) -> atomicBoolean.set(true), null, null);
    }
}
