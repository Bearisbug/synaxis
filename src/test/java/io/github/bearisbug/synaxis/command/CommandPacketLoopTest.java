package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import org.junit.jupiter.api.Test;

/**
 * Where the items of a loop redirect to in the commands packet.
 */
public class CommandPacketLoopTest {

    /**
     * The items of a loop go back to the node holding the loop, whatever sibling follows the loop: node ids are
     * given after all of a node's descendants, so the next id after the loop is not the parent's.
     */
    @Test
    public void loopItemsGoBackToTheNodeHoldingTheLoopWhateverFollowsIt() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Loop("l", ArgumentType.Literal("a"), ArgumentType.Literal("b")))
                .append(ArgumentType.Integer("n"))
                .build();
        CommandPacketTest.assertPacketGraph("""
                foo a b=%
                n=! INTEGER 0
                0->foo
                foo->a b n
                a b+>foo
                """, graph);
    }

    /**
     * Under an enum the node holding the loop is one entry per constant, all with the same children; the items go
     * back to the first.
     */
    @Test
    public void loopItemsUnderAnEnumGoBackToItsFirstEntry() {
        var graph = Graph.builder(ArgumentType.Literal("foo"))
                .append(ArgumentType.Enum("letter", CommandPacketTest.Letter.class),
                        b -> b.append(ArgumentType.Loop("l", ArgumentType.Literal("x"), ArgumentType.Literal("y"))))
                .append(ArgumentType.Integer("n"))
                .build();
        CommandPacketTest.assertPacketGraph("""
                foo x y=%
                a b c=§
                n=! INTEGER 0
                0->foo
                foo->a b c n
                a b c->x y
                x y+>a
                """, graph);
    }
}
