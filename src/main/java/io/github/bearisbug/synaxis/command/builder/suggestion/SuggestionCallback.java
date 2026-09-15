package io.github.bearisbug.synaxis.command.builder.suggestion;

import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.CommandContext;

@FunctionalInterface
public interface SuggestionCallback {
    void apply(CommandSender sender, CommandContext context, Suggestion suggestion);
}
