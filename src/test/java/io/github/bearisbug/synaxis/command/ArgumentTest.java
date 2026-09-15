package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.suggestion.Suggestion;
import io.github.bearisbug.synaxis.command.builder.suggestion.SuggestionEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArgumentTest {
    private static final ServerProcess PROCESS = ServerProcess.of();


    @Test
    public void testParseSelf() {
        assertEquals("example", Argument.parse(new ServerSender(PROCESS), ArgumentType.String("example")));
        assertEquals(55, Argument.parse(new ServerSender(PROCESS), ArgumentType.Integer("55")));
    }

    @Test
    public void testCallback() {
        var arg = ArgumentType.String("id");

        assertFalse(arg.hasErrorCallback());
        arg.setCallback((_, _) -> {
        });
        assertTrue(arg.hasErrorCallback());
    }

    @Test
    public void testDefaultValue() {
        var arg = ArgumentType.String("id");

        assertFalse(arg.isOptional());
        arg.setDefaultValue("default value");
        assertTrue(arg.isOptional());
        assertEquals("default value", arg.getDefaultValue().apply(new ServerSender(PROCESS)));
    }

    @Test
    public void testSuggestionCallback() {
        var arg = ArgumentType.String("id");

        assertFalse(arg.hasSuggestion());

        arg.setSuggestionCallback((_, _, suggestion) -> suggestion.addEntry(new SuggestionEntry("entry")));
        assertTrue(arg.hasSuggestion());

        Suggestion suggestion = new Suggestion("input", 2, 4);
        arg.getSuggestionCallback().apply(new ServerSender(PROCESS), new CommandContext("input"), suggestion);

        assertEquals(suggestion.getEntries(), List.of(new SuggestionEntry("entry")));
    }
}