package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.Graph.Node;
import io.github.bearisbug.synaxis.command.builder.ArgumentCallback;
import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.command.builder.CommandData;
import io.github.bearisbug.synaxis.command.builder.CommandExecutor;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.condition.CommandCondition;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.command.builder.suggestion.Suggestion;
import io.github.bearisbug.synaxis.command.builder.suggestion.SuggestionCallback;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

final class CommandParserImpl implements CommandParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandParserImpl.class);
    static final CommandParserImpl PARSER = new CommandParserImpl();
    /**
     * Appended by {@code TabCompleteListener#getSuggestion} to a request ending with a space, so that the argument
     * after it is parsed and its suggestion callback found.
     */
    private static final String SUGGESTION_PLACEHOLDER = "\0";

    static final class Chain {
        @Nullable CommandExecutor defaultExecutor = null;
        @Nullable SuggestionCallback suggestionCallback = null;
        final ArrayDeque<NodeResult> nodeResults = new ArrayDeque<>();
        final List<CommandCondition> conditions = new ArrayList<>();
        final List<CommandExecutor> globalListeners = new ArrayList<>();
        /**
         * The node whose argument failed to parse deepest down this chain, with its exception. Null when none did, or
         * when a sibling failed as deep, so that the error belongs to no single argument. A fork does not inherit it.
         */
        @Nullable Failure failure = null;

        void append(NodeResult result) {
            this.nodeResults.add(result);
            final Graph.Execution execution = result.node.execution();
            if (execution != null) {
                // Create condition chain
                final CommandCondition condition = execution.condition();
                if (condition != null) conditions.add(condition);

                // Track default executor
                final CommandExecutor defExec = execution.defaultExecutor();
                if (defExec != null) defaultExecutor = defExec;

                // Merge global listeners
                final CommandExecutor globalListener = execution.globalListener();
                if (globalListener != null) globalListeners.add(globalListener);
            }
        }

        CommandCondition mergedConditions() {
            return (sender, commandString) -> {
                for (CommandCondition condition : conditions) {
                    if (!condition.canUse(sender, commandString)) return false;
                }
                return true;
            };
        }

        CommandExecutor mergedGlobalExecutors() {
            return (sender, context) -> globalListeners.forEach(x -> x.apply(sender, context));
        }

        Map<String, ArgumentResult<Object>> collectArguments() {
            return nodeResults.stream()
                    .skip(2) // skip root node and command
                    .collect(Collectors.toUnmodifiableMap(NodeResult::name, NodeResult::argumentResult));
        }

        List<Argument<?>> getArgs() {
            return nodeResults.stream().map(x -> x.node.argument()).collect(Collectors.toList());
        }

        int size() {
            return nodeResults.size();
        }

        /**
         * How far down the chain parsed: the number of successful results after the first one, the root, up to the
         * first that is not a success. For example a chain [root, foo, bar, baz] whose input "foo bar" lacks the
         * last argument has a depth of 2. Tells which of several failed chains got the furthest.
         *
         * @return the successful depth
         * @see #size() the number of all the results
         */
        int depth() {
            return successPrefix();
        }

        /**
         * The last result of the successful part of the chain, the one {@link #depth()} counts up to.
         *
         * @return the last successful result, or null if the depth is zero: no node after the root parsed
         * @see #depth()
         */
        @Nullable NodeResult lastSuccessfulResult() {
            final int prefix = successPrefix();
            return prefix == 0 ? null : nodeResults.stream().skip(prefix).findFirst().orElseThrow();
        }

        private int successPrefix() {
            return (int) nodeResults.stream().skip(1)
                    .takeWhile(result -> result.argumentResult() instanceof ArgumentResult.Success<?>)
                    .count();
        }

        Chain() {
        }

        Chain(@Nullable CommandExecutor defaultExecutor,
              @Nullable SuggestionCallback suggestionCallback,
              ArrayDeque<NodeResult> nodeResults,
              List<CommandCondition> conditions,
              List<CommandExecutor> globalListeners) {
            this.defaultExecutor = defaultExecutor;
            this.suggestionCallback = suggestionCallback;
            this.nodeResults.addAll(nodeResults);
            this.conditions.addAll(conditions);
            this.globalListeners.addAll(globalListeners);
        }

        Chain fork() {
            return new Chain(defaultExecutor, suggestionCallback, nodeResults, conditions, globalListeners);
        }
    }

    @Override
    public CommandParser.Result parse(CommandSender sender, Graph graph, String input) {
        final CommandStringReader reader = new CommandStringReader(input);
        Node parent = graph.root();

        NodeResult result = parseNode(sender, parent, new Chain(), reader);
        Chain chain = result.chain();

        NodeResult lastNodeResult = chain.nodeResults.peekLast();
        if (lastNodeResult == null) return UnknownCommandResult.INSTANCE;
        Node lastNode = lastNodeResult.node;

        // The error callback of the argument that alone failed deepest is called instead of any executor, for a sender
        // its syntax lets see the argument
        final Failure failure = chain.failure;
        if (failure != null) {
            final ArgumentCallback callback = failure.node().argument().getCallback();
            final Graph.Execution execution = failure.node().execution();
            if (callback != null && (execution == null || execution.test(sender)))
                return InvalidCommand.failed(input, chain, callback, failure.exception());
        }

        if (result.argumentResult instanceof ArgumentResult.Success<?>) {
            final Graph.Execution execution = lastNode.execution();
            final CommandExecutor executor = execution == null ? null : execution.executor();
            if (executor != null) return ValidCommand.executor(input, chain, executor, execution.executorCondition());
        }

        // If here, then the command failed or didn't have an executor, then this isn't a known command
        if (chain.depth() < 1) return UnknownCommandResult.INSTANCE;

        // Look for a default executor, or give up if we got nowhere
        if (lastNode.equals(parent)) return UnknownCommandResult.INSTANCE;

        final @Nullable ValidCommand defaultExecutor = ValidCommand.defaultExecutor(input, chain);
        if (defaultExecutor != null) return defaultExecutor;

        return InvalidCommand.invalid(input, chain);
    }

    private static NodeResult parseNode(CommandSender sender, Node node, Chain chain, CommandStringReader reader) {
        chain = chain.fork();
        Argument<?> argument = node.argument();
        int start = reader.cursor();

        if (reader.hasRemaining()) {
            SuggestionCallback suggestionCallback = argument.getSuggestionCallback();
            ArgumentResult<?> result = parseArgument(sender, argument, reader);
            @SuppressWarnings("unchecked")
            NodeResult nodeResult = new NodeResult(node, chain, (ArgumentResult<Object>) result);
            chain.append(nodeResult);
            if (suggestionCallback != null) chain.suggestionCallback = suggestionCallback;
            if (chain.size() == 1) { // If this is the root node (usually "Literal<>")
                reader.cursor(start);
            } else {
                if (!(result instanceof ArgumentResult.Success<?>)) {
                    if (result instanceof ArgumentResult.IncompatibleType<?> incompatible)
                        chain.failure = new Failure(node, incompatible.exception());
                    reader.cursor(start);
                    return nodeResult;
                }
            }
        } else {
            // Nothing left, yet we're still being asked to parse? There must be defaults then
            Function<CommandSender, ?> defaultSupplier = node.argument().getDefaultValue();
            if (defaultSupplier != null) {
                Object value = defaultSupplier.apply(sender);
                ArgumentResult<Object> argumentResult = new ArgumentResult.Success<>(value, "");
                chain.append(new NodeResult(node, chain, argumentResult));
                // Add the default to the chain, and then carry on dealing with this node
            } else {
                // Still being asked to parse yet there's nothing left, syntax error.
                return new NodeResult(node, chain, new ArgumentResult.SyntaxError<>("Not enough arguments", "", -1));
            }
        }
        // Successfully matched this node's argument
        start = reader.cursor();

        NodeResult error = null;
        for (Node child : node.next()) {
            NodeResult childResult = parseNode(sender, child, chain, reader);
            if (childResult.terminal() && childResult.argumentResult instanceof ArgumentResult.Success<Object>) {
                // Assume that there is only one successful node for a given chain of arguments
                return childResult;
            } else {
                // Traverse through the node results to find the last
                // node with a valid argument
                final int childDepth = childResult.chain().depth();
                final boolean isDeeper = error != null && childDepth > error.chain().depth();

                if (childDepth > 0 && (error == null || isDeeper)) {
                    // If this is the base argument (e.g. "teleport" in /teleport) then
                    // do not report an argument to be incompatible, since the more
                    // correct thing would be to say that the command is unknown.
                    if (!(childResult.chain.size() == 2 && childResult.argumentResult instanceof ArgumentResult.IncompatibleType<?>)) {
                        // If the last successful result is null, throw an exception instead of having unintended behaviour
                        NodeResult lastSuccess = Objects.requireNonNull(childResult.chain().lastSuccessfulResult());
                        final SuggestionCallback deepestSuggestion = childResult.chain().suggestionCallback;
                        final Failure failure = childResult.chain().failure;

                        if (deepestSuggestion != null || failure != null) {
                            final Chain errorChain = lastSuccess.chain().fork();
                            if (deepestSuggestion != null) errorChain.suggestionCallback = deepestSuggestion;
                            errorChain.failure = failure;

                            lastSuccess = new NodeResult(lastSuccess.node(), errorChain, lastSuccess.argumentResult());
                        }
                        error = lastSuccess.asPartial();
                    }
                } else if (error != null && childDepth == error.chain().depth()) {
                    // A sibling failed as deep: the error belongs to no single argument
                    error.chain().failure = null;
                }
                reader.cursor(start);
            }
        }
        // None were successful. Either incompatible types, or syntax error. It doesn't matter to us, though
        // Try to execute this node
        final Graph.Execution execution = node.execution();
        final CommandExecutor executor = execution == null ? null : execution.executor();
        if (executor == null) {
            // Stuck here with no executor
            if (error != null) {
                return error;
            } else {
                return chain.nodeResults.peekLast();
            }
        }

        if (reader.hasRemaining()) {
            // Trailing data is a syntax error
            // Can get to here if there's a default executor even if the user is still typing the command
            // So let's supply the next argument's suggestion callback if it exists
            Node returnNode = node;
            SuggestionCallback suggestionCallback = argument.getSuggestionCallback();
            List<Node> nextNodes = node.next();
            if (!nextNodes.isEmpty()) {
                returnNode = nextNodes.getFirst();
                suggestionCallback = returnNode.argument().getSuggestionCallback();
            }
            NodeResult nodeResult = new NodeResult(returnNode, error == null ? chain : error.chain,
                    new ArgumentResult.SyntaxError<>("Command has trailing data", "", -1));
            if (suggestionCallback != null) chain.suggestionCallback = suggestionCallback;
            // Not appended: the chain holds the result of this node already, this one only reports the trailing data
            return nodeResult;
        }

        // Command was successful!
        return chain.nodeResults.peekLast();
    }

    record UnknownCommandResult() implements Result.UnknownCommand {
        private static final CommandParser.Result INSTANCE = new UnknownCommandResult();

        @Override
        public ExecutableCommand executable() {
            return UnknownExecutableCmd.INSTANCE;
        }

        @Override
        public @Nullable Suggestion suggestion(CommandSender sender) {
            return null;
        }

        @Override
        public List<Argument<?>> args() {
            return List.of();
        }
    }

    sealed interface InternalKnownCommand extends Result.KnownCommand {
        String input();

        CommandCondition condition();

        Map<String, ArgumentResult<Object>> arguments();

        CommandExecutor globalListener();

        @Nullable SuggestionCallback suggestionCallback();

        @Override
        default @Nullable Suggestion suggestion(CommandSender sender) {
            final SuggestionCallback callback = suggestionCallback();
            if (callback == null) return null;
            final int lastSpace = input().lastIndexOf(" ");
            // Offsets into the text of the client, which TabCompleteListener stripped of its leading '/': + 1 skips
            // the space, + 1 more puts the '/' back. The length counts the placeholder the listener appends after a
            // trailing space, which the client tolerates.
            final Suggestion suggestion = new Suggestion(input(), lastSpace + 2, input().length() - lastSpace - 1);
            final CommandContext context = createCommandContext(input(), withoutPlaceholder(arguments()));
            callback.apply(sender, context, suggestion);
            return suggestion;
        }
    }

    record InvalidCommand(String input, CommandCondition condition, @Nullable ArgumentCallback callback,
                          ArgumentResult.SyntaxError<?> error,
                          Map<String, ArgumentResult<Object>> arguments, CommandExecutor globalListener,
                          @Nullable SuggestionCallback suggestionCallback, List<Argument<?>> args)
            implements InternalKnownCommand, Result.KnownCommand.Invalid {

        static InvalidCommand invalid(String input, Chain chain) {
            return new InvalidCommand(input, chain.mergedConditions(),
                    null/*todo command syntax callback*/,
                    new ArgumentResult.SyntaxError<>("Command has trailing data.", null, -1),
                    chain.collectArguments(), chain.mergedGlobalExecutors(), chain.suggestionCallback, chain.getArgs());
        }

        static InvalidCommand failed(String input, Chain chain, ArgumentCallback callback, ArgumentSyntaxException exception) {
            return new InvalidCommand(input, chain.mergedConditions(), callback,
                    new ArgumentResult.SyntaxError<>(exception.getMessage(), exception.getInput(), exception.getErrorCode()),
                    chain.collectArguments(), chain.mergedGlobalExecutors(), chain.suggestionCallback, chain.getArgs());
        }

        @Override
        public ExecutableCommand executable() {
            return new InvalidExecutableCmd(condition, globalListener, callback, error, input, arguments);
        }
    }

    record ValidCommand(String input, CommandCondition condition, CommandExecutor executor,
                        Map<String, ArgumentResult<Object>> arguments,
                        CommandExecutor globalListener, @Nullable SuggestionCallback suggestionCallback,
                        List<Argument<?>> args)
            implements InternalKnownCommand, Result.KnownCommand.Valid {

        static @Nullable ValidCommand defaultExecutor(String input, Chain chain) {
            CommandExecutor defaultExecutor = null;

            for (Iterator<NodeResult> it = chain.nodeResults.descendingIterator(); it.hasNext(); ) {
                final NodeResult node = it.next();
                defaultExecutor = node.chain().defaultExecutor;
                if (defaultExecutor != null) break;
            }

            if (defaultExecutor == null) return null;
            return new ValidCommand(input, chain.mergedConditions(), defaultExecutor, chain.collectArguments(),
                    chain.mergedGlobalExecutors(), chain.suggestionCallback, chain.getArgs());
        }

        /**
         * @param executorCondition the condition of the syntax the executor belongs to, checked after those of the
         *                          commands on the chain
         */
        static ValidCommand executor(String input, Chain chain, CommandExecutor executor,
                                     @Nullable CommandCondition executorCondition) {
            final CommandCondition commands = chain.mergedConditions();
            final CommandCondition condition = executorCondition == null ? commands : (sender, commandString) ->
                    commands.canUse(sender, commandString) && executorCondition.canUse(sender, commandString);
            return new ValidCommand(input, condition, executor, chain.collectArguments(), chain.mergedGlobalExecutors(),
                    chain.suggestionCallback, chain.getArgs());
        }

        @Override
        public ExecutableCommand executable() {
            return new ValidExecutableCmd(condition, globalListener, executor, input, arguments);
        }
    }

    record UnknownExecutableCmd() implements ExecutableCommand {
        static final ExecutableCommand INSTANCE = new UnknownExecutableCmd();

        @Override
        public ExecutableCommand.Result execute(CommandSender sender) {
            return ExecutionResultImpl.UNKNOWN;
        }
    }

    record ValidExecutableCmd(CommandCondition condition, CommandExecutor globalListener, CommandExecutor executor,
                              String input,
                              Map<String, ArgumentResult<Object>> arguments) implements ExecutableCommand {
        @Override
        public ExecutableCommand.Result execute(CommandSender sender) {
            final CommandContext context = createCommandContext(input, arguments);

            globalListener().apply(sender, context);

            if (!condition.canUse(sender, input())) {
                return ExecutionResultImpl.PRECONDITION_FAILED;
            }
            try {
                executor().apply(sender, context);
                return new ExecutionResultImpl(ExecutableCommand.Result.Type.SUCCESS, context.getReturnData());
            } catch (Exception e) {
                LOGGER.error("An exception was encountered while executing command: {}",
                        StringUtils.escapeForLog(input()), e);
                return ExecutionResultImpl.EXECUTOR_EXCEPTION;
            }
        }
    }

    record InvalidExecutableCmd(CommandCondition condition, CommandExecutor globalListener, ArgumentCallback callback,
                                ArgumentResult.SyntaxError<?> error, String input,
                                Map<String, ArgumentResult<Object>> arguments) implements ExecutableCommand {
        @Override
        public ExecutableCommand.Result execute(CommandSender sender) {
            globalListener().apply(sender, createCommandContext(input, arguments));

            if (!condition.canUse(sender, input())) {
                return ExecutionResultImpl.PRECONDITION_FAILED;
            }
            if (callback != null) {
                try {
                    callback.apply(sender, new ArgumentSyntaxException(error.message(), error.input(), error.code()));
                } catch (Exception e) {
                    LOGGER.error("An exception was encountered while executing command: {}",
                            StringUtils.escapeForLog(input()), e);
                    return ExecutionResultImpl.EXECUTOR_EXCEPTION;
                }
            }
            return ExecutionResultImpl.INVALID_SYNTAX;
        }
    }

    private static CommandContext createCommandContext(String input, Map<String, ArgumentResult<Object>> arguments) {
        final CommandContext context = new CommandContext(input);
        for (var entry : arguments.entrySet()) {
            final String identifier = entry.getKey();
            final ArgumentResult<Object> value = entry.getValue();

            final Object argOutput = value instanceof ArgumentResult.Success<Object> success ? success.value() : null;
            final String argInput = value instanceof ArgumentResult.Success<Object> success ? success.input() : "";

            context.setArg(identifier, argOutput, argInput);
        }
        return context;
    }

    /**
     * The arguments as a suggestion callback sees them. After a trailing space {@code TabCompleteListener} appends
     * {@link #SUGGESTION_PLACEHOLDER} so that the next argument gets parsed at all: the argument read from it is empty
     * text, a string array ends with an empty word.
     */
    private static Map<String, ArgumentResult<Object>> withoutPlaceholder(
            Map<String, ArgumentResult<Object>> arguments) {
        final Map<String, ArgumentResult<Object>> result = new HashMap<>(arguments);
        result.replaceAll((_, argument) -> {
            if (!(argument instanceof ArgumentResult.Success<Object> success)
                    || !success.input().endsWith(SUGGESTION_PLACEHOLDER)) return argument;
            Object value = success.value();
            if (value instanceof String text) {
                value = stripPlaceholder(text);
            } else if (value instanceof String[] words && words.length > 0) {
                final String[] copy = words.clone();
                copy[copy.length - 1] = stripPlaceholder(copy[copy.length - 1]);
                value = copy;
            }
            return new ArgumentResult.Success<>(value, stripPlaceholder(success.input()));
        });
        return result;
    }

    private static String stripPlaceholder(String text) {
        return text.endsWith(SUGGESTION_PLACEHOLDER) ? text.substring(0, text.length() - 1) : text;
    }

    record ExecutionResultImpl(Type type, @Nullable CommandData commandData) implements ExecutableCommand.Result {
        static final ExecutableCommand.Result UNKNOWN = new ExecutionResultImpl(Type.UNKNOWN, null);
        static final ExecutableCommand.Result EXECUTOR_EXCEPTION = new ExecutionResultImpl(Type.EXECUTOR_EXCEPTION, null);
        static final ExecutableCommand.Result PRECONDITION_FAILED = new ExecutionResultImpl(Type.PRECONDITION_FAILED, null);
        static final ExecutableCommand.Result INVALID_SYNTAX = new ExecutionResultImpl(Type.INVALID_SYNTAX, null);
    }

    /**
     * @param terminal whether this result really parsed to the end of a branch. A partial result only carries the
     *                 deepest successful node of a branch that failed further down, so it must not be mistaken for a
     *                 branch that matched, otherwise the sibling branches are never visited.
     */
    private record NodeResult(Node node, Chain chain, ArgumentResult<Object> argumentResult, boolean terminal) {
        NodeResult(Node node, Chain chain, ArgumentResult<Object> argumentResult) {
            this(node, chain, argumentResult, true);
        }

        NodeResult asPartial() {
            return terminal ? new NodeResult(node, chain, argumentResult, false) : this;
        }

        String name() {
            return node.argument().getId();
        }
    }

    private record Failure(Node node, ArgumentSyntaxException exception) {
    }

    /**
     * Reads the input word by word. The cursor stays within {@code [0, input.length()]}: after the last word it is the
     * length of the input.
     */
    static final class CommandStringReader {
        private final String input;
        private int cursor = 0;

        CommandStringReader(String input) {
            this.input = input;
        }

        boolean hasRemaining() {
            return cursor < input.length();
        }

        String readWord() {
            final String input = this.input;
            final int cursor = this.cursor;

            final int i = input.indexOf(' ', cursor);
            if (i == -1) {
                this.cursor = input.length();
                return input.substring(cursor);
            }
            final String read = input.substring(cursor, i);
            this.cursor += read.length() + 1;
            return read;
        }

        String readRemaining() {
            final String input = this.input;
            final String result = input.substring(cursor);
            this.cursor = input.length();
            return result;
        }

        int cursor() {
            return cursor;
        }

        void cursor(int cursor) {
            assert cursor >= 0 && cursor <= input.length();
            this.cursor = cursor;
        }
    }

    // ARGUMENT

    private static <T> ArgumentResult<T> parseArgument(CommandSender sender, Argument<T> argument, CommandStringReader reader) {
        // Handle specific type without loop
        try {
            // Single word argument
            if (!argument.allowSpace()) {
                final String word = reader.readWord();
                return new ArgumentResult.Success<>(argument.parse(sender, word), word);
            }
            // Complete input argument
            if (argument.useRemaining()) {
                final String remaining = reader.readRemaining();
                return new ArgumentResult.Success<>(argument.parse(sender, remaining), remaining);
            }
        } catch (ArgumentSyntaxException e) {
            return new ArgumentResult.IncompatibleType<>(e);
        }
        // Bruteforce
        assert argument.allowSpace() && !argument.useRemaining();
        StringBuilder current = new StringBuilder(reader.readWord());
        ArgumentSyntaxException last;
        while (true) {
            try {
                final String input = current.toString();
                return new ArgumentResult.Success<>(argument.parse(sender, input), input);
            } catch (ArgumentSyntaxException e) {
                // The longest input tried is the one the error is reported for
                last = e;
                if (!reader.hasRemaining()) break;
                current.append(" ");
                current.append(reader.readWord());
            }
        }
        return new ArgumentResult.IncompatibleType<>(last);
    }

    private sealed interface ArgumentResult<R> {
        record Success<T>(T value, String input)
                implements ArgumentResult<T> {
        }

        record IncompatibleType<T>(ArgumentSyntaxException exception)
                implements ArgumentResult<T> {
        }

        record SyntaxError<T>(String message, @Nullable String input, int code)
                implements ArgumentResult<T> {
        }
    }
}
