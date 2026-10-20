package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandDispatcher;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.arguments.number.ArgumentNumber;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType.Literal;
import static io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType.Word;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandParseTest {
    private static final ServerProcess PROCESS = ServerProcess.of();


    @Test
    public void emptyCommand() {
        var graph = Graph.merge(Graph.builder(Literal("foo"), createExecutor(new AtomicBoolean())).build());
        assertUnknown(graph, "");
    }

    @Test
    public void singleParameterlessCommand() {
        final AtomicBoolean b = new AtomicBoolean();
        var foo = Graph.merge(Graph.builder(Literal("foo"), createExecutor(b)).build());

        // even though we add extra, it's still /foo so we'll call the default executor
        assertValid(foo, "foo bar baz", b);
        assertUnknown(foo, "bar");
    }

    @Test
    public void twoParameterlessCommand() {
        final AtomicBoolean b = new AtomicBoolean();
        final AtomicBoolean b1 = new AtomicBoolean();
        var graph = Graph.merge(
                Graph.builder(Literal("foo"), createExecutor(b)).build(),
                Graph.builder(Literal("bar"), createExecutor(b1)).build()
        );
        assertValid(graph, "foo", b);
        assertValid(graph, "bar", b1);
        assertUnknown(graph, "baz");
    }

    @Test
    public void singleCommandWithMultipleSyntax() {
        final AtomicBoolean add = new AtomicBoolean();
        final AtomicBoolean action = new AtomicBoolean();
        var foo = Graph.merge(Graph.builder(Literal("foo"))
                .append(Literal("add"),
                        x -> x.append(Word("name"), createExecutor(add)))
                .append(Word("type").from("inc", "dec"),
                        x -> x.append(ArgumentType.Integer("num"), createExecutor(action)))
                .build());

        // Regular/Expected usage of the command
        assertValid(foo, "foo add test", add);
        assertValid(foo, "foo add inc", add);
        assertValid(foo, "foo add 157", add);
        assertValid(foo, "foo inc 157", action);
        assertValid(foo, "foo dec 157", action);

        // Since foo doesn't have a default executor, we want these to throw a syntax error
        assertSyntaxError(foo, "foo 15");
        assertSyntaxError(foo, "foo asd");

        // Foo and the inc argument both don't have a default executor (only a regular executor for inc), so these will fail
        assertSyntaxError(foo, "foo inc");
        assertSyntaxError(foo, "foo inc asd");

        // A valid command is provided, even if we have extra data we'll still accept it
        assertValid(foo, "foo inc 15 dec", action);
        assertValid(foo, "foo inc 15 20", action);

        // None of these are registered commands, make sure the correct command valuation is provided back
        assertUnknown(foo, "bar");
        assertUnknown(foo, "add");
    }

    @Test
    public void singleCommandOptionalArgs() {
        final AtomicBoolean b = new AtomicBoolean();
        final AtomicReference<String> expectedFirstArg = new AtomicReference<>("T");
        var foo = Graph.merge(Graph.builder(Literal("foo"))
                .append(Word("a").setDefaultValue("A"),
                        x -> x.append(Word("b").setDefaultValue("B"),
                                x1 -> x1.append(Word("c").setDefaultValue("C"),
                                        x2 -> x2.append(Word("d").setDefaultValue("D"),
                                                new GraphImpl.ExecutionImpl(null, null, null,
                                                        (_, context) -> {
                                                            b.set(true);
                                                            assertEquals(expectedFirstArg.get(), context.get("a"));
                                                            assertEquals("B", context.get("b"));
                                                            assertEquals("C", context.get("c"));
                                                            assertEquals("D", context.get("d"));
                                                        }, null, null)))))
                .build());
        assertValid(foo, "foo T", b);
        expectedFirstArg.set("A");
        assertValid(foo, "foo", b);
    }

    @Test
    public void singleCommandSingleEnumArg() {
        enum A {a, b}
        final AtomicBoolean rootExecutor = new AtomicBoolean();
        final AtomicBoolean argExecutor = new AtomicBoolean();
        var foo = Graph.merge(Graph.builder(Literal("foo"), createExecutor(rootExecutor))
                .append(ArgumentType.Enum("test", A.class), createExecutor(argExecutor))
                .build());
        assertValid(foo, "foo a", argExecutor);
        assertValid(foo, "foo b", argExecutor);
        assertValid(foo, "foo c", rootExecutor);
        assertValid(foo, "foo", rootExecutor);
    }

    @Test
    public void aliasWithoutArgs() {
        final AtomicBoolean b = new AtomicBoolean();
        var foo = Graph.merge(Graph.builder(Word("").from("foo", "bar"), createExecutor(b))
                .build());
        assertValid(foo, "foo", b);
        assertValid(foo, "bar", b);
        assertUnknown(foo, "test");
    }

    @Test
    public void aliasWithArgs() {
        final AtomicBoolean b = new AtomicBoolean();
        var foo = Graph.merge(Graph.builder(Word("").from("foo", "bar"))
                .append(ArgumentType.Integer("test"), createExecutor(b))
                .build());
        assertValid(foo, "foo 1", b);
        assertValid(foo, "bar 1", b);
        assertSyntaxError(foo, "foo");
        assertSyntaxError(foo, "bar");
    }

    /**
     * An argument that fails to parse calls its error callback with its own error code, in place of the default
     * executor of the command.
     */
    @Test
    public void anArgumentThatFailsCallsItsErrorCallbackInPlaceOfTheDefaultExecutor() {
        final List<Integer> codes = new CopyOnWriteArrayList<>();
        final AtomicInteger defaults = new AtomicInteger();
        final Command foo = new Command("foo");
        foo.setDefaultExecutor((_, _) -> defaults.incrementAndGet());
        final Argument<Integer> number = ArgumentType.Integer("n").between(1, 10);
        foo.setArgumentCallback((_, exception) -> codes.add(exception.getErrorCode()), number);
        foo.addSyntax((_, _) -> { }, number);
        final CommandDispatcher dispatcher = new CommandDispatcher(PROCESS);
        dispatcher.register(foo);
        final ServerSender sender = new ServerSender(PROCESS);

        assertEquals(CommandResult.Type.INVALID_SYNTAX, dispatcher.execute(sender, "foo 11").getType());
        assertEquals(CommandResult.Type.INVALID_SYNTAX, dispatcher.execute(sender, "foo abc").getType());
        assertEquals(List.of(ArgumentNumber.TOO_HIGH_ERROR, ArgumentNumber.NOT_NUMBER_ERROR), codes);
        assertEquals(0, defaults.get(), "the default executor ran in place of the callback");

        // A missing argument is no error of the argument
        assertEquals(CommandResult.Type.SUCCESS, dispatcher.execute(sender, "foo").getType());
        assertEquals(1, defaults.get());
        assertEquals(2, codes.size());
    }

    /**
     * Without a callback, or when two syntaxes fail on the same word so that none of them owns the error, the
     * default executor still runs.
     */
    @Test
    public void theDefaultExecutorRunsWhenNoSingleArgumentOwnsTheError() {
        final List<Integer> codes = new CopyOnWriteArrayList<>();
        final AtomicInteger defaults = new AtomicInteger();
        final Command plain = new Command("plain");
        plain.setDefaultExecutor((_, _) -> defaults.incrementAndGet());
        plain.addSyntax((_, _) -> { }, ArgumentType.Integer("n").between(1, 10));
        final Command shared = new Command("shared");
        shared.setDefaultExecutor((_, _) -> defaults.incrementAndGet());
        final Argument<Integer> number = ArgumentType.Integer("n").between(1, 10);
        number.setCallback((_, exception) -> codes.add(exception.getErrorCode()));
        shared.addSyntax((_, _) -> { }, number);
        shared.addSyntax((_, _) -> { }, ArgumentType.Word("mode").from("on", "off"));
        final CommandDispatcher dispatcher = new CommandDispatcher(PROCESS);
        dispatcher.register(plain);
        dispatcher.register(shared);
        final ServerSender sender = new ServerSender(PROCESS);

        assertEquals(CommandResult.Type.SUCCESS, dispatcher.execute(sender, "plain 11").getType());
        assertEquals(CommandResult.Type.SUCCESS, dispatcher.execute(sender, "shared 11").getType());
        assertEquals(2, defaults.get());
        assertEquals(List.of(), codes, "11 is no mode either, the error is not the number's alone");
    }

    @Test
    public void aCallbackThatThrowsCancelsTheCommand() {
        final Command foo = new Command("foo");
        final Argument<Integer> number = ArgumentType.Integer("n").between(1, 10);
        number.setCallback((_, _) -> {
            throw new IllegalStateException("the callback failed");
        });
        foo.addSyntax((_, _) -> { }, number);
        final CommandDispatcher dispatcher = new CommandDispatcher(PROCESS);
        dispatcher.register(foo);

        assertEquals(CommandResult.Type.CANCELLED, dispatcher.execute(new ServerSender(PROCESS), "foo 11").getType());
    }

    /**
     * The error callback belongs to the syntax holding the argument: a sender its condition refuses does not get the
     * callback, the default executor runs as it does without one.
     */
    @Test
    public void aSenderTheSyntaxRefusesDoesNotGetTheCallback() {
        final List<Integer> codes = new CopyOnWriteArrayList<>();
        final AtomicInteger defaults = new AtomicInteger();
        final Command foo = new Command("foo");
        foo.setDefaultExecutor((_, _) -> defaults.incrementAndGet());
        final Argument<Integer> number = ArgumentType.Integer("n").between(1, 10);
        number.setCallback((_, exception) -> codes.add(exception.getErrorCode()));
        foo.addConditionalSyntax((_, _) -> false, (_, _) -> { }, number);
        final CommandDispatcher dispatcher = new CommandDispatcher(PROCESS);
        dispatcher.register(foo);
        final ServerSender sender = new ServerSender(PROCESS);

        assertEquals(CommandResult.Type.CANCELLED, dispatcher.execute(sender, "foo 5").getType());
        assertEquals(CommandResult.Type.SUCCESS, dispatcher.execute(sender, "foo 11").getType());
        assertEquals(List.of(), codes, "the syntax refuses the sender, the callback of its argument is not for it");
        assertEquals(1, defaults.get());
    }

    /**
     * An argument read across spaces is tried on ever longer input; its callback gets the error of the longest.
     */
    @Test
    public void theCallbackOfAnArgumentReadAcrossSpacesGetsTheErrorOfTheLongestInput() {
        final List<String> inputs = new CopyOnWriteArrayList<>();
        final Command foo = new Command("foo");
        final Argument<String> text = ArgumentType.String("text");
        text.setCallback((_, exception) -> inputs.add(exception.getInput()));
        foo.addSyntax((_, _) -> { }, text);
        final CommandDispatcher dispatcher = new CommandDispatcher(PROCESS);
        dispatcher.register(foo);

        assertEquals(CommandResult.Type.INVALID_SYNTAX,
                dispatcher.execute(new ServerSender(PROCESS), "foo \"abc def").getType());
        assertEquals(List.of("\"abc def"), inputs);
    }

    /**
     * A quoted string holding escaped quotes is tried on ever longer input. A prefix whose closing quote is escaped
     * is no string, so the parser goes on to the whole of it instead of running the syntax with the prefix.
     */
    @Test
    public void aQuotedStringHoldingEscapedQuotesIsReadWhole() {
        final AtomicReference<String> said = new AtomicReference<>();
        final Command foo = new Command("foo");
        final Argument<String> text = ArgumentType.String("text");
        foo.addSyntax((_, context) -> said.set(context.get(text)), text);
        final CommandDispatcher dispatcher = new CommandDispatcher(PROCESS);
        dispatcher.register(foo);

        assertEquals(CommandResult.Type.SUCCESS,
                dispatcher.execute(new ServerSender(PROCESS), "foo \"say \\\"hi\\\" now\"").getType());
        assertEquals("say \"hi\" now", said.get());
    }

    private static void assertSyntaxError(Graph graph, String input) {
        assertInstanceOf(CommandParser.Result.KnownCommand.Invalid.class, parseCommand(graph, input));
    }

    private static void assertUnknown(Graph graph, String input) {
        assertInstanceOf(CommandParser.Result.UnknownCommand.class, parseCommand(graph, input));
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
