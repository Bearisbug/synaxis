package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands packet a player gets while it spawns is built before the player has an instance. A node that cannot
 * be written for that player there, because its condition throws (such as one reading {@code getInstance()}) or
 * because its argument has no parser, is left out instead of failing the spawn, which used to leave the client on the
 * loading screen.
 */
@EnvTest
public class CommandTreeAtSpawnIntegrationTest {

    @Test
    public void aConditionThatThrowsAtSpawnHidesItsNodeAndThePlayerSpawns(Env env) {
        final Instance instance = env.createFlatInstance();
        final Command home = new Command("home");
        home.setCondition((sender, _) -> ((Player) sender).getInstance().getUuid() != null);
        home.setDefaultExecutor((_, _) -> { });
        final Command broken = new Command("broken");
        broken.setDefaultExecutor((_, _) -> { });
        broken.addConditionalSyntax((_, _) -> {
            throw new IllegalStateException("the condition of broken now");
        }, (_, _) -> { }, ArgumentType.Literal("now"));
        final Command fine = new Command("fine");
        fine.setDefaultExecutor((_, _) -> { });
        env.process().command().register(home, broken, fine);

        final TestConnection connection = env.createConnection();
        final Collector<DeclareCommandsPacket> atSpawn = connection.trackIncoming(DeclareCommandsPacket.class);
        final Player player = connection.connect(instance, new Pos(0, 42, 0));
        assertSame(instance, player.getInstance(), "the player did not spawn");
        final Set<String> spawned = names(atSpawn);
        assertTrue(spawned.containsAll(Set.of("fine", "broken")), spawned.toString());
        assertFalse(spawned.contains("home"), "the condition of home threw, the node has to be left out");
        assertFalse(spawned.contains("now"), "the condition of broken now threw, the node has to be left out");
        env.tick();

        // Once the player has an instance the condition of home passes
        final Collector<DeclareCommandsPacket> refreshed = connection.trackIncoming(DeclareCommandsPacket.class);
        player.refreshCommands();
        final Set<String> after = names(refreshed);
        assertTrue(after.contains("home"), after.toString());
        assertFalse(after.contains("now"), after.toString());
    }

    @Test
    public void anArgumentWithoutAParserIsLeftOutAndThePlayerSpawns(Env env) {
        final Instance instance = env.createFlatInstance();
        final Command roll = new Command("roll");
        roll.setDefaultExecutor((_, _) -> { });
        roll.addSyntax((_, _) -> { }, new Argument<String>("sides") {
            @Override
            public String parse(CommandSender sender, String input) {
                return input;
            }

            @Override
            public @Nullable ArgumentParserType parser() {
                return null;
            }
        });
        env.process().command().register(roll);

        final TestConnection connection = env.createConnection();
        final Collector<DeclareCommandsPacket> atSpawn = connection.trackIncoming(DeclareCommandsPacket.class);
        final Player player = connection.connect(instance, new Pos(0, 42, 0));
        assertSame(instance, player.getInstance(), "the player did not spawn");
        final Set<String> spawned = names(atSpawn);
        assertTrue(spawned.contains("roll"), spawned.toString());
        assertFalse(spawned.contains("sides"), "sides has no parser to write, the node has to be left out");
    }

    private static Set<String> names(Collector<DeclareCommandsPacket> trees) {
        return trees.collect().getLast().nodes().stream().map(node -> node.name).collect(Collectors.toSet());
    }
}
