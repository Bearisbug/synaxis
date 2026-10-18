package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The redirect of a command argument with a shortcut in the commands packet a player gets while it spawns.
 */
@EnvTest
public class CommandShortcutIntegrationTest {

    /**
     * A command argument with a shortcut redirects to the command the shortcut parses to. When none does, or the one
     * that does is hidden from the player, the node redirects to the root instead of failing the spawn.
     */
    @Test
    public void aShortcutToNoCommandOrToOneHiddenFromThePlayerRedirectsToTheRoot(Env env) {
        final Instance instance = env.createFlatInstance();
        final Command secret = new Command("secret");
        secret.setCondition((_, _) -> false);
        secret.setDefaultExecutor((_, _) -> { });
        final Command alias = new Command("alias");
        alias.addSyntax((_, _) -> { }, ArgumentType.Command("hidden").setShortcut("secret"));
        alias.addSyntax((_, _) -> { }, ArgumentType.Literal("other"), ArgumentType.Command("missing").setShortcut("nosuch"));
        env.process().command().register(secret, alias);

        final TestConnection connection = env.createConnection();
        final Collector<DeclareCommandsPacket> atSpawn = connection.trackIncoming(DeclareCommandsPacket.class);
        final Player player = connection.connect(instance, new Pos(0, 42, 0));
        assertSame(instance, player.getInstance(), "the player did not spawn");
        final DeclareCommandsPacket packet = atSpawn.collect().getLast();
        for (String name : List.of("hidden", "missing")) {
            final DeclareCommandsPacket.Node node = packet.nodes().stream().filter(n -> name.equals(n.name))
                    .findFirst().orElseThrow();
            assertEquals(packet.rootIndex(), node.redirectedNode, name + " redirects to the root");
        }
    }
}
