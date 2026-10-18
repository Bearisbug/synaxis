package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandExecutor;
import io.github.bearisbug.synaxis.command.builder.CommandSyntax;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentCommand;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentLiteral;
import io.github.bearisbug.synaxis.command.builder.condition.CommandCondition;
import io.github.bearisbug.synaxis.command.builder.exception.IllegalCommandStructureException;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType.Literal;
import static io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType.Word;

record GraphImpl(NodeImpl root) implements Graph {
    static GraphImpl fromCommand(Command command) {
        return new GraphImpl(NodeImpl.command(command));
    }

    /**
     * Checks what the parser cannot follow, which would otherwise fail every run of the command or leave a syntax
     * that never runs: an argument id appearing twice on one path, two syntaxes ending at one node, or two arguments
     * of one class and id at one place configured differently.
     *
     * @param command the command to register
     * @throws IllegalCommandStructureException if an argument id appears twice on one path, or two syntaxes end at
     *                                          one node with a different executor or condition
     * @throws IllegalArgumentException         if two arguments of one class and id at one place are configured
     *                                          differently
     */
    static void checkStructure(Command command) {
        ConversionNode.fromCommand(command, List.of(command.getName()));
    }

    static Graph merge(Collection<Command> commands) {
        return new GraphImpl(NodeImpl.rootCommands(commands));
    }

    static GraphImpl merge(List<Graph> graphs) {
        final List<Node> children = graphs.stream().map(Graph::root).toList();
        final NodeImpl root = new NodeImpl(Literal(""), null, children);
        return new GraphImpl(root);
    }

    @Override
    public boolean compare(Graph graph, Comparator comparator) {
        return compare(root, graph.root(), comparator);
    }

    record BuilderImpl(Argument<?> argument, List<BuilderImpl> children, Execution execution) implements Graph.Builder {
        public BuilderImpl(Argument<?> argument, Execution execution) {
            this(argument, new ArrayList<>(), execution);
        }

        @Override
        public Graph.Builder append(
                Argument<?> argument, @Nullable Execution execution,
                Consumer<Graph.Builder> consumer
        ) {
            BuilderImpl builder = new BuilderImpl(argument, execution);
            consumer.accept(builder);
            this.children.add(builder);
            return this;
        }

        @Override
        public Graph.Builder append(Argument<?> argument, @Nullable Execution execution) {
            this.children.add(new BuilderImpl(argument, List.of(), execution));
            return this;
        }

        @Override
        public GraphImpl build() {
            return new GraphImpl(NodeImpl.fromBuilder(this));
        }
    }

    record NodeImpl(Argument<?> argument, @Nullable ExecutionImpl execution, List<Graph.Node> next) implements Graph.Node {
        NodeImpl(Argument<?> argument, @Nullable ExecutionImpl execution, List<Graph.Node> next) {
            this.argument = argument;
            this.execution = execution;
            this.next = next.stream().sorted(nodePriority).toList();
        }

        static NodeImpl fromBuilder(BuilderImpl builder) {
            final List<BuilderImpl> children = builder.children;
            Node[] nodes = new NodeImpl[children.size()];
            for (int i = 0; i < children.size(); i++) nodes[i] = fromBuilder(children.get(i));
            return new NodeImpl(builder.argument, (ExecutionImpl) builder.execution, List.of(nodes));
        }

        static NodeImpl command(Command command) {
            return ConversionNode.fromCommand(command).toNode();
        }

        static NodeImpl rootCommands(Collection<Command> commands) {
            return ConversionNode.rootConv(commands).toNode();
        }

        private static final java.util.Comparator<Node> nodePriority = (node1, node2) -> {
            int node1Value = argumentValue(node1.argument());
            int node2Value = argumentValue(node2.argument());
            return Integer.compare(node1Value, node2Value);
        };

        private static int argumentValue(Argument<?> argument) {
            if (argument.getClass() == ArgumentCommand.class) return -3000;
            if (argument.getClass() == ArgumentLiteral.class) return -2000;
            return -1000;
        }
    }

    record ExecutionImpl(
            @UnknownNullability Predicate<CommandSender> predicate,
            @Nullable CommandExecutor defaultExecutor,
            @Nullable CommandExecutor globalListener,
            @Nullable CommandExecutor executor,
            @Nullable CommandCondition condition,
            @Nullable CommandCondition executorCondition
    ) implements Execution {
        @Override
        public boolean test(CommandSender commandSender) {
            return predicate.test(commandSender);
        }

        static ExecutionImpl fromCommand(Command command) {
            final CommandExecutor defaultExecutor = command.getDefaultExecutor();
            final CommandCondition defaultCondition = command.getCondition();

            CommandExecutor executor = defaultExecutor;
            CommandCondition executorCondition = null;
            for (var syntax : command.getSyntaxes()) {
                if (syntax.getArguments().length == 0) {
                    // Its condition guards its executor only, not the syntaxes with arguments nor the default executor
                    executor = syntax.getExecutor();
                    executorCondition = syntax.getCommandCondition();
                    break;
                }
            }
            final CommandExecutor globalListener = (sender, context) -> command.globalListener(sender, context,
                                                                                               context.getInput());

            return new ExecutionImpl(
                    commandSender -> defaultCondition == null || defaultCondition.canUse(commandSender, null),
                    defaultExecutor, globalListener, executor, defaultCondition, executorCondition);
        }

        static ExecutionImpl fromSyntax(CommandSyntax syntax) {
            final CommandExecutor executor = syntax.getExecutor();
            final CommandCondition condition = syntax.getCommandCondition();
            return new ExecutionImpl(commandSender -> condition == null || condition.canUse(commandSender, null),
                                     null, null, executor, null, condition);
        }

        /**
         * The gate of an intermediate node of a syntax: it carries the syntax condition so the node can be filtered
         * out, but no executor, since the syntax does not terminate there.
         */
        static ExecutionImpl fromSyntaxPath(CommandSyntax syntax) {
            final CommandCondition condition = syntax.getCommandCondition();
            return new ExecutionImpl(commandSender -> condition == null || condition.canUse(commandSender, null),
                                     null, null, null, null, null);
        }

        /**
         * A node can be shared by several syntaxes. It stays visible as long as one of them accepts the sender, and
         * it keeps the first executor installed on it, with the condition of the syntax that executor belongs to.
         */
        static ExecutionImpl merge(@Nullable ExecutionImpl current, ExecutionImpl added) {
            if (current == null) return added;
            final ExecutionImpl ending = current.executor() != null ? current : added;
            return new ExecutionImpl(
                    commandSender -> current.test(commandSender) || added.test(commandSender),
                    current.defaultExecutor() != null ? current.defaultExecutor() : added.defaultExecutor(),
                    current.globalListener() != null ? current.globalListener() : added.globalListener(),
                    ending.executor(),
                    current.condition() != null ? current.condition() : added.condition(),
                    ending.executorCondition());
        }

        /**
         * Whether two executions both end a syntax at one node, with a different executor or condition: only the
         * first one would ever run.
         */
        static boolean conflict(ExecutionImpl current, ExecutionImpl added) {
            return current.executor() != null && added.executor() != null
                    && !(Objects.equals(current.executor(), added.executor())
                    && Objects.equals(current.executorCondition(), added.executorCondition()));
        }
    }

    private static final class ConversionNode {
        final Argument<?> argument;
        ExecutionImpl execution;
        final Map<Argument<?>, ConversionNode> nextMap;

        ConversionNode(Argument<?> argument, @Nullable ExecutionImpl execution, Map<Argument<?>, ConversionNode> nextMap) {
            this.argument = argument;
            this.execution = execution;
            this.nextMap = nextMap;
        }

        ConversionNode(Argument<?> argument, ExecutionImpl execution) {
            this(argument, execution, new LinkedHashMap<>());
        }

        private NodeImpl toNode() {
            Node[] nodes = new NodeImpl[nextMap.size()];
            int i = 0;
            for (var entry : nextMap.values()) nodes[i++] = entry.toNode();
            return new NodeImpl(argument, execution, List.of(nodes));
        }

        static ConversionNode fromCommand(Command command) {
            return fromCommand(command, null);
        }

        /**
         * @param path the ids from the command being registered down to this one, the first being the name of the
         *             registered command; null to build the graph as it is, without checking its structure
         * @throws IllegalCommandStructureException if {@code path} is given and an argument id appears twice on one
         *                                          path, or two syntaxes end at the same node
         * @throws IllegalArgumentException         if {@code path} is given and two arguments of one class and id
         *                                          at the same place are configured differently
         */
        static ConversionNode fromCommand(Command command, @Nullable List<String> path) {
            ConversionNode root = new ConversionNode(commandToArgument(command), ExecutionImpl.fromCommand(command));
            if (path != null) checkZeroArgumentSyntaxes(command, path);
            // Subcommands
            for (Command subcommand : command.getSubcommands()) {
                root.nextMap.put(commandToArgument(subcommand),
                        fromCommand(subcommand, path == null ? null : appended(path, subcommand.getName())));
            }
            // Syntaxes
            for (CommandSyntax syntax : command.getSyntaxes()) {
                ConversionNode syntaxNode = root;
                List<String> syntaxPath = path;
                final Argument<?>[] arguments = syntax.getArguments();
                for (int i = 0; i < arguments.length; i++) {
                    final Argument<?> arg = arguments[i];
                    // Compare by index, not by instance: the arguments of a parsed command are keyed by id, so an
                    // instance appearing twice in one syntax is refused when the command is registered
                    final boolean last = i == arguments.length - 1;
                    final ExecutionImpl ex = last ? ExecutionImpl.fromSyntax(syntax) : ExecutionImpl.fromSyntaxPath(syntax);
                    if (syntaxPath != null) syntaxPath = appended(syntaxPath, arg.getId());
                    ConversionNode next = syntaxNode.nextMap.get(arg);
                    if (next == null) {
                        next = new ConversionNode(arg, ex);
                        syntaxNode.nextMap.put(arg, next);
                    } else {
                        if (syntaxPath != null) checkMerge(next, arg, ex, syntaxPath);
                        next.execution = ExecutionImpl.merge(next.execution, ex);
                    }
                    syntaxNode = next;
                }
            }
            return root;
        }

        static ConversionNode rootConv(Collection<Command> commands) {
            Map<Argument<?>, ConversionNode> next = new LinkedHashMap<>(commands.size());
            for (Command command : commands) {
                final ConversionNode conv = fromCommand(command);
                next.put(conv.argument, conv);
            }
            return new ConversionNode(Literal(""), null, next);
        }

        /** The arguments of a parsed command are keyed by id, from below the registered command down. */
        private static List<String> appended(List<String> path, String id) {
            if (path.subList(1, path.size()).contains(id)) {
                throw new IllegalCommandStructureException("command '" + path.getFirst() + "': the argument id '" + id
                        + "' appears twice on the path " + String.join(" ", path) + " " + id);
            }
            final List<String> appended = new ArrayList<>(path);
            appended.add(id);
            return appended;
        }

        private static void checkZeroArgumentSyntaxes(Command command, List<String> path) {
            ExecutionImpl first = null;
            for (CommandSyntax syntax : command.getSyntaxes()) {
                if (syntax.getArguments().length != 0) continue;
                final ExecutionImpl ex = ExecutionImpl.fromSyntax(syntax);
                if (first == null) first = ex;
                else if (ExecutionImpl.conflict(first, ex)) throw twoSyntaxesEnd(path);
            }
        }

        private static void checkMerge(ConversionNode node, Argument<?> arg, ExecutionImpl ex, List<String> path) {
            if (node.argument != arg && !node.argument.sameConfiguration(arg)) {
                throw new IllegalArgumentException("command '" + path.getFirst() + "': two arguments with the id '"
                        + arg.getId() + "' at " + String.join(" ", path)
                        + " are configured differently; give one of them another id");
            }
            if (ExecutionImpl.conflict(node.execution, ex)) throw twoSyntaxesEnd(path);
        }

        private static IllegalCommandStructureException twoSyntaxesEnd(List<String> path) {
            return new IllegalCommandStructureException("command '" + path.getFirst() + "': two syntaxes end at "
                    + String.join(" ", path) + ", only the first registered would run");
        }
    }

    static Argument<String> commandToArgument(Command command) {
        final String[] aliases = command.getNames();
        if (aliases.length == 1) return Literal(aliases[0]);
        return Word(command.getName()).from(command.getNames());
    }

    static boolean compare(Node first, Node second, Comparator comparator) {
        return switch (comparator) {
            case TREE -> {
                if (!compareExecution(first, second)) yield false;
                if (!first.argument().equals(second.argument())) yield false;
                if (first.next().size() != second.next().size()) yield false;
                for (int i = 0; i < first.next().size(); i++) {
                    if (!compare(first.next().get(i), second.next().get(i), comparator)) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    private static boolean compareExecution(Node firstNode, Node secondNode) {
        Execution first = firstNode.execution(), second = secondNode.execution();
        boolean firstExecutor = first != null && first.executor() != null,
                firstCondition = first != null && first.condition() != null,
                firstExecutorCondition = first != null && first.executorCondition() != null;
        boolean secondExecutor = second != null && second.executor() != null,
                secondCondition = second != null && second.condition() != null,
                secondExecutorCondition = second != null && second.executorCondition() != null;
        return firstExecutor == secondExecutor &&
                firstCondition == secondCondition &&
                firstExecutorCondition == secondExecutorCondition;
    }
}
