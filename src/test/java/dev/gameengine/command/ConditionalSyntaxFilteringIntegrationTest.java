package dev.gameengine.command;

import dev.gameengine.command.builder.Command;
import dev.gameengine.command.builder.arguments.ArgumentType;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Player;
import dev.gameengine.network.packet.server.play.DeclareCommandsPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * The condition of a syntax has to gate every node the syntax creates, not only the one carrying the executor.
 */
@EnvTest
public class ConditionalSyntaxFilteringIntegrationTest {

    @Test
    public void conditionalSyntaxFalseFiltersEveryArgument(Env env) {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> false, null, ArgumentType.Literal("reload"), ArgumentType.Literal("hard"));
        assertFiltering(env, foo, """
                foo=%
                0->foo
                """);
    }

    @Test
    public void conditionalSyntaxTrueKeepsEveryArgument(Env env) {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> true, null, ArgumentType.Literal("reload"), ArgumentType.Literal("hard"));
        assertFiltering(env, foo, """
                foo reload hard=%
                0->foo
                foo->reload
                reload->hard
                """);
    }

    /**
     * A node shared by several syntaxes stays visible as long as one of them accepts the sender.
     */
    @Test
    public void sharedPrefixKeepsTheAllowedSyntax(Env env) {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> false, null, ArgumentType.Literal("reload"), ArgumentType.Literal("hard"));
        foo.addConditionalSyntax((_, _) -> true, null, ArgumentType.Literal("reload"), ArgumentType.Literal("soft"));
        assertFiltering(env, foo, """
                foo reload soft=%
                0->foo
                foo->reload
                reload->soft
                """);
    }

    /**
     * A syntax terminating on a node an earlier syntax only passes through still has to be executable.
     */
    @Test
    public void sharedPrefixKeepsTheShorterSyntaxExecutable(Env env) {
        final Command foo = new Command("foo");
        foo.addSyntax((_, _) -> {
        }, ArgumentType.Literal("reload"), ArgumentType.Literal("hard"));
        foo.addSyntax((_, _) -> {
        }, ArgumentType.Literal("reload"));
        assertFiltering(env, foo, """
                foo=%
                reload='reload' executable
                hard='hard' executable
                0->foo
                foo->reload
                reload->hard
                """);
    }

    private static void assertFiltering(Env env, Command command, String expectedStructure) {
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
        final DeclareCommandsPacket packet = GraphConverter.createPacket(
                env.process().command(), Graph.merge(Set.of(command)), player);
        CommandTestUtils.assertPacket(packet, expectedStructure);
    }
}
