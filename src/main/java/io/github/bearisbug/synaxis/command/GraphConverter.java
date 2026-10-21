package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentCommand;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentEnum;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentGroup;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentLiteral;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentLoop;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentWord;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

final class GraphConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphConverter.class);

    private GraphConverter() {
        //no instance
    }

    @Contract("_, _, _ -> new")
    public static DeclareCommandsPacket createPacket(CommandManager manager, Graph graph, @Nullable Player player) {
        List<DeclareCommandsPacket.Node> nodes = new ArrayList<>();
        List<BiConsumer<Graph, Integer>> redirects = new ArrayList<>();
        Map<Argument<?>, Integer> argToPacketId = new HashMap<>();
        final int rootId = append(manager, graph.root(), nodes, redirects, null, new AtomicInteger(), player,
                argToPacketId, null)[0];
        for (var r : redirects) {
            r.accept(graph, rootId);
        }
        return new DeclareCommandsPacket(nodes, rootId);
    }

    /**
     * The id of a node is its index in {@code to}, given in post-order: a node gets its id after all of its
     * descendants.
     *
     * @param redirect where the node redirects to, set for the items of a loop
     * @param parent   the id of the parent node, set once the parent has one, which the items of a loop go back to
     * @param command  the name of the command the node belongs to, null for the root
     */
    private static int[] append(CommandManager manager, Graph.Node graphNode, List<DeclareCommandsPacket.Node> to,
                                List<BiConsumer<Graph, Integer>> redirects, @Nullable AtomicInteger redirect,
                                AtomicInteger parent, @Nullable Player player, Map<Argument<?>, Integer> argToPacketId,
                                @Nullable String command) {
        final Graph.Execution execution = graphNode.execution();
        if (player != null && execution != null) {
            if (!visible(execution, player, graphNode, command)) return new int[0];
        }

        final Argument<?> argument = graphNode.argument();
        // What map and filter wrap decides the shape of the node, the wrapper only gives its id and suggestions
        final Argument<?> shape = argument.unwrap();
        final List<Graph.Node> children = graphNode.next();

        final DeclareCommandsPacket.Node node = new DeclareCommandsPacket.Node();
        // The id of this node, set by every branch below that writes it: the parent the children get
        final AtomicInteger self = new AtomicInteger();
        final IntArrayList packetNodeChildren = new IntArrayList(children.size());
        for (int i = 0; i < children.size(); i++) {
            final int[] append = append(manager, children.get(i), to, redirects, redirect, self, player,
                    argToPacketId, command != null ? command : children.get(i).argument().getId());
            if (append.length > 0) {
                argToPacketId.put(children.get(i).argument(), append[0]);
            }
            packetNodeChildren.addElements(packetNodeChildren.size(), append);
        }
        node.children = packetNodeChildren.toIntArray();

        boolean isExecutable = graphNode.execution() != null && graphNode.execution().executor() != null;

        if (shape instanceof ArgumentLiteral literal) {
            if (literal.getId().isEmpty()) {
                node.flags = 0; //root
            } else {
                node.flags = literal(isExecutable, redirect != null);
                node.name = argument.getId();
                if (redirect != null) {
                    redirects.add((_, _) -> node.redirectedNode = redirect.get());
                }
            }
            final int id = to.size();
            to.add(node);
            self.set(id);
            return new int[]{id};
        } else {
            if (shape instanceof ArgumentCommand argCmd) {
                node.flags = literal(isExecutable, true);
                node.name = argument.getId();
                final String shortcut = argCmd.getShortcut();
                if (shortcut.isEmpty()) {
                    redirects.add((_, root) -> node.redirectedNode = root);
                } else {
                    redirects.add((graph, root) -> {
                        var sender = player == null ? manager.getConsoleSender() : player;
                        final List<Argument<?>> args = CommandParser.parser().parse(sender, graph, shortcut).args();
                        // No command answers to the shortcut, or the one that does is hidden from this player
                        Integer target = null;
                        if (!args.isEmpty()) {
                            final Argument<?> last = args.getLast();
                            if (!last.allowSpace()) target = argToPacketId.get(last);
                            else if (args.size() >= 2) target = argToPacketId.get(args.get(args.size() - 2));
                        }
                        node.redirectedNode = target != null ? target : root;
                    });
                }
                final int id = to.size();
                to.add(node);
                self.set(id);
                return new int[]{id};
            } else if (shape instanceof ArgumentEnum<?> || (shape instanceof ArgumentWord word && word.hasRestrictions())) {
                List<String> entries = shape instanceof ArgumentEnum<?> ?
                        ((ArgumentEnum<?>) shape).entries() :
                        Arrays.stream(((ArgumentWord) shape).getRestrictions()).toList();
                final int[] res = new int[entries.size()];
                for (int i = 0; i < res.length; i++) {
                    String entry = entries.get(i);
                    final DeclareCommandsPacket.Node subNode = new DeclareCommandsPacket.Node();
                    subNode.children = node.children;
                    subNode.flags = literal(isExecutable, redirect != null);
                    subNode.name = entry;
                    if (redirect != null) {
                        redirects.add((_, _) -> subNode.redirectedNode = redirect.get());
                    }
                    res[i] = to.size();
                    to.add(subNode);
                }
                // Every entry has the children of the node, so any of them can be gone back to
                if (res.length > 0) self.set(res[0]);
                return res;
            } else if (shape instanceof ArgumentGroup special) {
                List<Argument<?>> entries = special.group();
                int[] res = null;
                int[] last = new int[0];
                for (int i = 0; i < entries.size(); i++) {
                    Argument<?> entry = entries.get(i);
                    if (i == entries.size() - 1) {
                        // Last will be the parent of next args
                        final int[] l = append(manager, new GraphImpl.NodeImpl(entry, null, List.of()), to, redirects,
                                redirect, self, player, argToPacketId, command);
                        for (int n : l) {
                            to.get(n).children = node.children;
                        }
                        for (int n : last) {
                            to.get(n).children = l;
                        }
                        // The last argument of the group carries the children of the node
                        if (l.length > 0) self.set(l[0]);
                        return res == null ? l : res;
                    } else if (i == 0) {
                        // First will be the children & parent of following
                        res = append(manager, new GraphImpl.NodeImpl(entry, null, List.of()), to, redirects, null,
                                self, player, argToPacketId, command);
                        last = res;
                    } else {
                        final int[] l = append(manager, new GraphImpl.NodeImpl(entry, null, List.of()), to, redirects,
                                null, self, player, argToPacketId, command);
                        for (int n : last) {
                            to.get(n).children = l;
                        }
                        last = l;
                    }
                }
                throw new RuntimeException("Arg group must have child args.");
            } else if (shape instanceof ArgumentLoop<?> special) {
                List<?> arguments = special.arguments();
                final IntArrayList res = new IntArrayList(arguments.size());
                for (int i = 0; i < arguments.size(); i++) {
                    Object arg = arguments.get(i);
                    // Each item goes back to the node holding the loop, for the next item
                    final int[] append = append(manager, new GraphImpl.NodeImpl((Argument<?>) arg, null, List.of()), to,
                            redirects, parent, self, player, argToPacketId, command);
                    res.addElements(res.size(), append);
                }
                if (!res.isEmpty()) self.set(res.getInt(0));
                return res.toIntArray();
            } else {
                final ArgumentParserType parser = shape.parser();
                if (parser == null) {
                    // Not thrown: the packet is also built while a player spawns, and a failure there stops the spawn
                    LOGGER.error("Command argument has no parser, the node is left out of the commands packet:"
                            + " command={} argument={} class={}", command, argument.getId(), shape.getClass().getName());
                    return new int[0];
                }
                final boolean hasSuggestion = argument.hasSuggestion();
                node.flags = arg(isExecutable, redirect != null, hasSuggestion);
                node.name = argument.getId();
                node.parser = parser;
                node.properties = shape.nodeProperties();
                if (redirect != null) {
                    redirects.add((_, _) -> node.redirectedNode = redirect.get());
                }
                if (hasSuggestion) {
                    node.suggestionsType = argument.suggestionType().getIdentifier();
                }
                final int id = to.size();
                to.add(node);
                self.set(id);
                return new int[]{id};
            }
        }
    }

    /**
     * Whether the condition of a node lets the player see it. A condition that throws hides the node: the packet is
     * built while the player spawns, before it has an instance, and a failure there would stop the spawn. A node
     * several syntaxes share can be hidden by one of their conditions throwing, even if another lets the player see it.
     */
    private static boolean visible(Graph.Execution execution, Player player, Graph.Node node, @Nullable String command) {
        try {
            return execution.test(player);
        } catch (RuntimeException e) {
            LOGGER.warn("Command condition failed, the node is left out of the commands of {}: command={} argument={}",
                    player.getUsername(), command, node.argument().getId(), e);
            return false;
        }
    }

    private static byte literal(boolean executable, boolean hasRedirect) {
        return DeclareCommandsPacket.getFlag(DeclareCommandsPacket.NodeType.LITERAL, executable, hasRedirect, false);
    }

    private static byte arg(boolean executable, boolean hasRedirect, boolean hasSuggestion) {
        return DeclareCommandsPacket.getFlag(DeclareCommandsPacket.NodeType.ARGUMENT, executable, hasRedirect,
                hasSuggestion);
    }
}
