package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandExecutor;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.exception.IllegalCommandStructureException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandManagerTest {
    private static final ServerProcess PROCESS = ServerProcess.of();


    @Test
    public void testCommandRegistration() {
        var manager = new CommandManager(PROCESS);

        var command = new Command("name1", "name2");

        manager.register(command);

        assertTrue(manager.commandExists("name1"));
        assertTrue(manager.commandExists("name2"));
        assertFalse(manager.commandExists("name3"));

        manager.unregister(command);

        assertFalse(manager.commandExists("name1"));
        assertFalse(manager.commandExists("name2"));
        assertFalse(manager.commandExists("name3"));
    }

    /**
     * A command is looked up by its lower-cased name but parsed by its name as given: one declared with an upper-case
     * letter would be found yet never run, one with a space or a leading slash never typed.
     */
    @Test
    public void aCommandNameIsLowerCaseTextWithoutWhitespaceOrALeadingSlash() {
        for (String name : List.of("Foo", "", "a b", "a\tb", "/x"))
            assertThrows(IllegalArgumentException.class, () -> new Command(name), name);
        assertThrows(IllegalArgumentException.class, () -> new Command("bar", "Baz"), "an alias follows the rule");

        var manager = new CommandManager(PROCESS);
        var command = new Command("probe:party", "party");
        command.setDefaultExecutor((_, _) -> { });
        manager.register(command);
        assertSame(command, manager.getCommand("PARTY"), "the lookup stays case-insensitive");
        assertThrows(IllegalStateException.class, () -> manager.register(new Command("party")));
        assertEquals(CommandResult.Type.SUCCESS, manager.executeServerCommand("probe:party").getType());

        manager.unregister(command);
        assertNull(manager.getCommand("party"));
    }

    /**
     * The arguments of a parsed command are keyed by id: an id twice on one path, a subcommand's name included, would
     * fail every run of the command, so registering it fails instead.
     */
    @Test
    public void anArgumentIdTwiceOnOnePathIsRefusedWhenTheCommandIsRegistered() {
        var manager = new CommandManager(PROCESS);
        var arena = new Command("arena");
        var map = new Command("map");
        map.addSyntax((_, _) -> { }, ArgumentType.Word("map"));
        arena.addSubcommand(map);
        assertThrows(IllegalCommandStructureException.class, () -> manager.register(arena));
        assertFalse(manager.commandExists("arena"), "a refused command is not registered");

        var foo = new Command("foo");
        var word = ArgumentType.Word("w");
        foo.addSyntax((_, _) -> { }, word, word);
        assertThrows(IllegalCommandStructureException.class, () -> manager.register(foo));

        var nested = new Command("nested");
        var level = new Command("level");
        level.addSubcommand(new Command("level"));
        nested.addSubcommand(level);
        assertThrows(IllegalCommandStructureException.class, () -> manager.register(nested));
    }

    /**
     * Two syntaxes ending at one node keep only the first executor; registering such a command fails instead. The
     * shorter syntax an optional argument adds counts, a shared prefix does not.
     */
    @Test
    public void twoSyntaxesEndingAtOnePlaceAreRefusedWhenTheCommandIsRegistered() {
        var manager = new CommandManager(PROCESS);
        var give = new Command("give");
        give.addSyntax((_, _) -> { }, ArgumentType.Word("target"), ArgumentType.Integer("n").setDefaultValue(1));
        give.addSyntax((_, _) -> { }, ArgumentType.Word("target"));
        assertThrows(IllegalCommandStructureException.class, () -> manager.register(give));

        var bare = new Command("bare");
        bare.addSyntax((_, _) -> { });
        bare.addSyntax((_, _) -> { });
        assertThrows(IllegalCommandStructureException.class, () -> manager.register(bare));

        var guarded = new Command("guarded");
        final CommandExecutor executor = (_, _) -> { };
        guarded.addSyntax(executor, ArgumentType.Literal("x"));
        guarded.addConditionalSyntax((_, _) -> true, executor, ArgumentType.Literal("x"));
        assertThrows(IllegalCommandStructureException.class, () -> manager.register(guarded),
                "the same executor under another condition");

        var sub = new Command("sub");
        sub.setDefaultExecutor((_, _) -> { });
        var parent = new Command("parent");
        parent.addSubcommand(sub);
        parent.addSyntax((_, _) -> { }, ArgumentType.Literal("sub"));
        assertThrows(IllegalCommandStructureException.class, () -> manager.register(parent));

        var twice = new Command("twice");
        twice.addSyntax(executor, ArgumentType.Literal("x"));
        twice.addSyntax(executor, ArgumentType.Literal("x"));
        twice.addSyntax((_, _) -> { }, ArgumentType.Literal("x"), ArgumentType.Literal("y"));
        manager.register(twice);
        assertEquals(CommandResult.Type.SUCCESS, manager.executeServerCommand("twice x y").getType());
    }

    enum Difficulty {EASY, HARD}

    enum Mode {CREATIVE, SURVIVAL}

    /**
     * Syntaxes holding arguments of one class and id at one place share the node of the first: a second configured
     * differently would never parse as it says, so registering it fails. Arguments configured the same, each syntax
     * creating its own, still share the node.
     */
    @Test
    public void argumentsSharingAPlaceAndAnIdButNotTheirConfigurationAreRefused() {
        var manager = new CommandManager(PROCESS);
        var mode = new Command("mode");
        mode.addSyntax((_, _) -> { }, ArgumentType.Enum("value", Difficulty.class));
        mode.addSyntax((_, _) -> { }, ArgumentType.Enum("value", Mode.class), ArgumentType.Word("target"));
        assertThrows(IllegalArgumentException.class, () -> manager.register(mode));

        var mapped = new Command("mapped");
        mapped.addSyntax((_, _) -> { }, ArgumentType.Integer("v").map(x -> x * 2));
        mapped.addSyntax((_, _) -> { }, ArgumentType.Word("v").map(String::length), ArgumentType.Word("w"));
        assertThrows(IllegalArgumentException.class, () -> manager.register(mapped));

        var words = new Command("words");
        words.addSyntax((_, _) -> { }, ArgumentType.Word("action").from("add", "remove"));
        words.addSyntax((_, _) -> { }, ArgumentType.Word("action").from("list"), ArgumentType.Word("page"));
        assertThrows(IllegalArgumentException.class, () -> manager.register(words));

        var ranges = new Command("ranges");
        ranges.addSyntax((_, _) -> { }, ArgumentType.Integer("n").between(1, 10));
        ranges.addSyntax((_, _) -> { }, ArgumentType.Integer("n").min(100), ArgumentType.Word("w"));
        assertThrows(IllegalArgumentException.class, () -> manager.register(ranges));

        final List<String> ran = new CopyOnWriteArrayList<>();
        var list = new Command("list");
        list.addSyntax((_, context) -> ran.add("add " + context.get("x")), ArgumentType.Literal("add"),
                ArgumentType.Word("x"));
        list.addSyntax((_, context) -> ran.add("add " + context.get("x") + " " + context.get("y")),
                ArgumentType.Literal("add"), ArgumentType.Word("x"), ArgumentType.Word("y"));
        manager.register(list);
        assertEquals(CommandResult.Type.SUCCESS, manager.executeServerCommand("list add a").getType());
        assertEquals(CommandResult.Type.SUCCESS, manager.executeServerCommand("list add a b").getType());
        assertEquals(List.of("add a", "add a b"), ran);
    }

    @Test
    public void testUnknownCommandCallback() {
        var manager = new CommandManager(PROCESS);

        AtomicBoolean check = new AtomicBoolean(false);
        manager.setUnknownCommandCallback((_, _) -> check.set(true));

        manager.register(new Command("valid_command"));

        manager.executeServerCommand("valid_command");
        assertFalse(check.get());

        manager.executeServerCommand("invalid_command");
        assertTrue(check.get());
    }

    @Test
    public void testSharedArgumentSyntaxABFirst() {
        var manager = new CommandManager(PROCESS);

        var checkA = new AtomicBoolean(false);
        var checkAB = new AtomicBoolean(false);

        var cmd = new Command("cmd");
        var argA = ArgumentType.String("a");
        var argB = ArgumentType.String("b");
        cmd.addSyntax((_, _) -> checkAB.set(true), argA, argB);
        cmd.addSyntax((_, _) -> checkA.set(true), argA);
        manager.register(cmd);

        var result = manager.executeServerCommand("cmd a");
        assertEquals(CommandResult.Type.SUCCESS, result.getType());
        assertTrue(checkA.get());
        assertFalse(checkAB.get());

        checkA.set(false); // these should be different tests
        checkAB.set(false);

        result = manager.executeServerCommand("cmd a b");
        assertEquals(CommandResult.Type.SUCCESS, result.getType());
        assertFalse(checkA.get());
        assertTrue(checkAB.get());
    }

    @Test
    public void testSharedArgumentSyntaxAFirst() {
        var manager = new CommandManager(PROCESS);

        var checkA = new AtomicBoolean(false);
        var checkAB = new AtomicBoolean(false);

        var cmd = new Command("cmd");
        var argA = ArgumentType.String("a");
        var argB = ArgumentType.String("b");
        cmd.addSyntax((_, _) -> checkA.set(true), argA);
        cmd.addSyntax((_, _) -> checkAB.set(true), argA, argB);
        manager.register(cmd);

        var result = manager.executeServerCommand("cmd a");
        assertEquals(CommandResult.Type.SUCCESS, result.getType());
        assertTrue(checkA.get());
        assertFalse(checkAB.get());

        checkA.set(false); // these should be different tests
        checkAB.set(false);

        result = manager.executeServerCommand("cmd a b");
        assertEquals(CommandResult.Type.SUCCESS, result.getType());
        assertFalse(checkA.get());
        assertTrue(checkAB.get());
    }

}
