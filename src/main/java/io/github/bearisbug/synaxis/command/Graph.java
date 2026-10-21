package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandExecutor;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.condition.CommandCondition;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

sealed interface Graph permits GraphImpl {
    static Builder builder(Argument<?> argument, @Nullable Execution execution) {
        return new GraphImpl.BuilderImpl(argument, execution);
    }

    static Builder builder(Argument<?> argument) {
        return new GraphImpl.BuilderImpl(argument, null);
    }

    static Graph fromCommand(Command command) {
        return GraphImpl.fromCommand(command);
    }

    static Graph merge(Collection<Command> commands) {
        return GraphImpl.merge(commands);
    }

    static Graph merge(List<Graph> graphs) {
        return GraphImpl.merge(graphs);
    }

    static Graph merge(Graph ... graphs) {
        return merge(List.of(graphs));
    }

    Node root();

    /**
     * Whether the other graph has the same tree: the same arguments in the same order, with an executor, a condition
     * and an executor condition at the same nodes.
     */
    boolean sameTree(Graph graph);

    sealed interface Node permits GraphImpl.NodeImpl {
        Argument<?> argument();

        @Nullable Execution execution();

        List<Node> next();
    }

    /**
     * What a node does for a sender. As a predicate it tells whether the sender sees the node: whether the commands
     * packet built for the sender holds it, and whether the error callback of its argument runs for the sender when
     * the argument fails. It asks the conditions with a null command string, and a node several syntaxes share is
     * seen when any of them lets the sender through. Running an executor asks {@link #condition()} and
     * {@link #executorCondition()} instead, with the command string.
     */
    sealed interface Execution extends Predicate<CommandSender> permits GraphImpl.ExecutionImpl {
        @Nullable CommandExecutor defaultExecutor();

        @Nullable CommandExecutor globalListener();

        /**
         * Non-null if the command at this point considered executable, must be present
         * on the last node of the syntax.
         */
        @Nullable CommandExecutor executor();

        /**
         * Non-null on the node of a command or subcommand that has a condition, which every command run through the
         * node has to pass
         */
        @Nullable CommandCondition condition();

        /**
         * Non-null on the node a syntax with a condition ends at, which only a run of {@link #executor()} has to
         * pass
         */
        @Nullable CommandCondition executorCondition();
    }

    sealed interface Builder permits GraphImpl.BuilderImpl {
        Builder append(Argument<?> argument, @Nullable Execution execution, Consumer<Builder> consumer);

        Builder append(Argument<?> argument, @Nullable Execution execution);

        default Builder append(Argument<?> argument, Consumer<Builder> consumer) {
            return append(argument, null, consumer);
        }

        default Builder append(Argument<?> argument) {
            return append(argument, (Execution) null);
        }

        Graph build();
    }
}
