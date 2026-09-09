package dev.gameengine.command;

import dev.gameengine.ServerProcess;
import dev.gameengine.command.builder.Command;
import dev.gameengine.command.builder.CommandResult;
import dev.gameengine.command.builder.arguments.ArgumentType;
import org.junit.jupiter.api.Test;

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
     * Names are stored as given but looked up lower-cased, so a command declared with an upper-case letter could
     * neither be found nor detected as a duplicate.
     */
    @Test
    public void testMixedCaseCommandRegistration() {
        var manager = new CommandManager(PROCESS);

        var command = new Command("Foo");
        manager.register(command);

        assertSame(command, manager.getCommand("Foo"));
        assertSame(command, manager.getCommand("foo"));
        assertTrue(manager.commandExists("FOO"));
        assertThrows(IllegalStateException.class, () -> manager.register(new Command("Foo")));

        manager.unregister(command);

        assertNull(manager.getCommand("foo"));
        assertFalse(manager.commandExists("Foo"));
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
