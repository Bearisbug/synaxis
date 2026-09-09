package dev.gameengine.command;

import dev.gameengine.ServerProcess;
import dev.gameengine.command.builder.Command;
import dev.gameengine.command.builder.CommandContext;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandTest {
    private static final ServerProcess PROCESS = ServerProcess.of();


    @Test
    public void testNames() {
        Command command = new Command("name1", "name2", "name3");

        assertEquals("name1", command.getName());
        assertArrayEquals(new String[]{"name2", "name3"}, command.getAliases());

        // command#getNames does not have any order guarantee, so that cannot be relied on
        assertEquals(Set.of("name1", "name2", "name3"), Set.of(command.getNames()));

        assertTrue(Command.isValidName(command, "name1"));
        assertTrue(Command.isValidName(command, "name2"));
    }

    @Test
    public void testGlobalListener() {
        var manager = new CommandManager(PROCESS);

        AtomicBoolean hasRun = new AtomicBoolean(false);

        var command = new Command("command") {
            @Override
            public void globalListener(CommandSender sender, CommandContext context, String command) {
                hasRun.set(true);
                context.setArg("key", "value", "value");
            }
        };

        manager.register(command);

        AtomicBoolean checkSet = new AtomicBoolean(false);
        command.setDefaultExecutor((_, context) -> checkSet.set("value".equals(context.get("key"))));

        manager.executeServerCommand("command");

        assertTrue(hasRun.get());
        assertTrue(checkSet.get());

    }
}
