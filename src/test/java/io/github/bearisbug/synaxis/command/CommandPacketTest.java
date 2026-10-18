package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandPacketTest {
    private static final ServerProcess PROCESS = ServerProcess.of();


    @Test
    public void singleCommandWithOneSyntax() {
        final Command foo = new Command("foo");
        foo.addSyntax(CommandPacketTest::dummyExecutor, ArgumentType.Integer("bar"));

        final DeclareCommandsPacket packet = GraphConverter.createPacket(new CommandManager(PROCESS), Graph.merge(Graph.fromCommand(foo)), null);
        assertEquals(3, packet.nodes().size());
        final DeclareCommandsPacket.Node root = packet.nodes().get(packet.rootIndex());
        assertNotNull(root);
        assertNodeType(DeclareCommandsPacket.NodeType.ROOT, root.flags);
        assertEquals(1, root.children.length);
        final DeclareCommandsPacket.Node cmd = packet.nodes().get(root.children[0]);
        assertNotNull(cmd);
        assertNodeType(DeclareCommandsPacket.NodeType.LITERAL, cmd.flags);
        assertEquals(1, cmd.children.length);
        assertEquals("foo", cmd.name);
        final DeclareCommandsPacket.Node arg = packet.nodes().get(cmd.children[0]);
        assertNotNull(arg);
        assertNodeType(DeclareCommandsPacket.NodeType.ARGUMENT, arg.flags);
        assertExecutable(arg.flags);
        assertEquals(0, arg.children.length);
        assertEquals("bar", arg.name);
    }

    @Test
    public void executeLike() {
        enum Dimension {OVERWORLD, THE_NETHER, THE_END}
        final Command execute = new Command("execute");
        execute.addSyntax(CommandPacketTest::dummyExecutor, ArgumentType.Loop("params",
                ArgumentType.Group("facing", ArgumentType.Literal("facing"), ArgumentType.RelativeVec3("pos")),
                ArgumentType.Group("at", ArgumentType.Literal("at"), ArgumentType.Entity("targets")),
                ArgumentType.Group("as", ArgumentType.Literal("as"), ArgumentType.Entity("targets")),
                ArgumentType.Group("in", ArgumentType.Literal("in"), ArgumentType.Enum("dimension", Dimension.class)),
                ArgumentType.Group("run", ArgumentType.Command("run"))
        ));
        var graph = Graph.fromCommand(execute);
        assertPacketGraph("""
                execute facing at as in run=%
                overworld the_nether the_end=§
                0->execute
                atEnt asEnt=targets ENTITY 0
                execute->facing at as in run
                at->atEnt
                as->asEnt
                in->overworld the_nether the_end
                pos=! VEC3
                facing->pos
                pos atEnt asEnt overworld the_nether the_end+>execute
                run+>0
                """, graph);
    }

    @Test
    public void singleCommandTwoEnum() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Enum("bar", Letter.class), b -> b.append(ArgumentType.Enum("baz", B.class)))
                .build();
        assertPacketGraph("""
                foo=%
                a b c d e f=§
                0->foo
                foo->a b c
                a b c->d e f
                """, graph);
    }

    @Test
    public void singleCommandRestrictedWord() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Word("bar").from("A", "B", "C"))
                .build();
        assertPacketGraph("""
                foo=%
                a b c=§
                0->foo
                foo->a b c
                """, graph);
    }

    @Test
    public void singleCommandWord() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Word("bar"))
                .build();
        assertPacketGraph("""
                foo=%
                bar=! STRING 0
                0->foo
                foo->bar
                """, graph);
    }

    @Test
    public void singleCommandCommandAfterEnum() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Enum("bar", Letter.class), b -> b.append(ArgumentType.Command("baz")))
                .build();
        assertPacketGraph("""
                foo baz=%
                a b c=§
                0->foo
                foo->a b c
                a b c->baz
                baz+>0
                """, graph);
    }

    @Test
    public void twoCommandIntEnumInt() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Integer("int1"), b -> b.append(ArgumentType.Enum("test", Letter.class), c -> c.append(ArgumentType.Integer("int2"))))
                .build();
        var graph2 = Graph.builder(ArgumentType.Literal("bar"))
                .append(ArgumentType.Integer("int3"), b -> b.append(ArgumentType.Enum("test", B.class), c -> c.append(ArgumentType.Integer("int4"))))
                .build();
        assertPacketGraph("""
                foo bar=%
                0->foo bar
                a b c d e f=§
                int1 int2 int3 int4=! INTEGER 0
                foo->int1
                bar->int3
                int1->a b c
                int3->d e f
                a b c->int2
                d e f->int4
                """, graph, graph2);
    }

    @Test
    public void singleCommandTwoGroupOfIntInt() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Group("1", ArgumentType.Integer("int1"), ArgumentType.Integer("int2")),
                        b -> b.append(ArgumentType.Group("2", ArgumentType.Integer("int3"), ArgumentType.Integer("int4"))))
                .build();
        assertPacketGraph("""
                foo=%
                int1 int2 int3 int4=! INTEGER 0
                0->foo
                foo->int1
                int1->int2
                int2->int3
                int3->int4
                """, graph);
    }
    @Test
    public void twoEnumAndOneLiteralChild() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Enum("a", Letter.class))
                .append(ArgumentType.Literal("l"))
                .append(ArgumentType.Enum("b", B.class))
                .build();
        assertPacketGraph("""
                foo l=%
                0->foo
                a b c d e f=§
                foo->a b c d e f l
                """, graph);
    }

    @Test
    public void commandAliasWithoutArg() {
        var graph = Graph.builder(ArgumentType.Word("foo").from("foo", "bar"))
                .build();
        assertPacketGraph("""
                foo bar=%
                0->foo bar
                """, graph);
    }

    @Test
    public void commandAliasWithArg() {
        var graph = Graph.builder(ArgumentType.Word("foo").from("foo", "bar"))
                .append(ArgumentType.Literal("l"))
                .build();
        assertPacketGraph("""
                foo bar l=%
                0->foo bar
                foo bar->l
                """, graph);
    }

    @Test
    public void cmdArgShortcut() {
        var foo = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.String("msg"))
                .build();
        var bar = Graph.builder(ArgumentType.Literal("bar"))
                .append(ArgumentType.Command("cmd").setShortcut("foo"))
                .build();
        assertPacketGraph("""
                foo bar cmd=%
                0->foo bar
                bar->cmd
                cmd+>foo
                foo->msg
                msg=! STRING 1
                """, foo, bar);
    }

    @Test
    public void cmdArgShortcutWithPartialArg() {
        var foo = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.String("msg"))
                .build();
        var bar = Graph.builder(ArgumentType.Literal("bar"))
                .append(ArgumentType.Command("cmd").setShortcut("foo \"prefix "))
                .build();
        assertPacketGraph("""
                foo bar cmd=%
                0->foo bar
                bar->cmd
                cmd+>foo
                foo->msg
                msg=! STRING 1
                """, foo, bar);
    }

    /**
     * map and filter wrap an argument in a class of their own; the packet keeps the nodes of the wrapped one. An
     * enum, a literal or a command argument has no parser to write, a word with restrictions is its literals.
     */
    @Test
    public void aMappedOrFilteredArgumentWritesTheNodesOfTheArgumentItWraps() {
        assertSameNodes(ArgumentType.Enum("bar", Letter.class), ArgumentType.Enum("bar", Letter.class).map(x -> x));
        assertSameNodes(ArgumentType.Enum("bar", Letter.class), ArgumentType.Enum("bar", Letter.class).filter(_ -> true));
        assertSameNodes(ArgumentType.Literal("bar"), ArgumentType.Literal("bar").map(x -> x));
        assertSameNodes(ArgumentType.Command("bar"), ArgumentType.Command("bar").map(x -> x));
        assertSameNodes(ArgumentType.Word("bar").from("a", "b"), ArgumentType.Word("bar").from("a", "b").filter(_ -> true));
    }

    @Test
    public void aMappedOrFilteredArgumentKeepsTheSuggestionTypeOfTheArgumentItWraps() {
        assertEquals("minecraft:summonable_entities", suggestionType(ArgumentType.EntityType("bar").map(x -> x)),
                "map dropped the suggestion type");
        assertEquals("minecraft:summonable_entities", suggestionType(ArgumentType.EntityType("bar").filter(_ -> true)),
                "filter dropped the suggestion type");
    }

    private static String suggestionType(Argument<?> argument) {
        final Command foo = new Command("foo");
        foo.addSyntax(CommandPacketTest::dummyExecutor, argument);
        final DeclareCommandsPacket packet = GraphConverter.createPacket(new CommandManager(PROCESS),
                Graph.merge(Graph.fromCommand(foo)), null);
        final DeclareCommandsPacket.Node bar = packet.nodes().stream().filter(node -> "bar".equals(node.name))
                .findFirst().orElseThrow();
        assertEquals(!bar.suggestionsType.isEmpty(), (bar.flags & DeclareCommandsPacket.HAS_SUGGESTION_TYPE) != 0,
                "the flag and the suggestion type differ");
        return bar.suggestionsType;
    }

    private static void assertSameNodes(Argument<?> plain, Argument<?> wrapped) {
        assertArrayEquals(packetBytes(plain), packetBytes(wrapped), "the nodes of " + wrapped);
    }

    private static byte[] packetBytes(Argument<?> argument) {
        final Command foo = new Command("foo");
        foo.addSyntax(CommandPacketTest::dummyExecutor, argument);
        final DeclareCommandsPacket packet = GraphConverter.createPacket(new CommandManager(PROCESS),
                Graph.merge(Graph.fromCommand(foo)), null);
        return NetworkBuffer.makeArray(buffer -> buffer.write(DeclareCommandsPacket.SERIALIZER, packet));
    }

    static void assertPacketGraph(String expected, Graph... graphs) {
        var packet = GraphConverter.createPacket(new CommandManager(PROCESS), Graph.merge(graphs), null);
        CommandTestUtils.assertPacket(packet, expected);
    }

    enum Letter {A, B, C}

    enum B {D, E, F}

    enum C {G, H, I, J, K}

    private static void assertNodeType(DeclareCommandsPacket.NodeType expected, byte flags) {
        assertEquals(expected, DeclareCommandsPacket.NodeType.values()[flags & 0x03]);
    }

    private static void assertExecutable(byte flags) {
        assertTrue((flags & DeclareCommandsPacket.IS_EXECUTABLE) != 0);
    }

    private static void dummyExecutor(CommandSender sender, CommandContext context) {
    }
}
