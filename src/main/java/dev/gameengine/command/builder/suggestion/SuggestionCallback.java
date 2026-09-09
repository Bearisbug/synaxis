package dev.gameengine.command.builder.suggestion;

import dev.gameengine.command.CommandSender;
import dev.gameengine.command.builder.CommandContext;

@FunctionalInterface
public interface SuggestionCallback {
    void apply(CommandSender sender, CommandContext context, Suggestion suggestion);
}
