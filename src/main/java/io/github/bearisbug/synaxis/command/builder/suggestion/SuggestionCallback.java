package io.github.bearisbug.synaxis.command.builder.suggestion;

import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.CommandContext;

/**
 * Offers the completions of an argument, see
 * {@link io.github.bearisbug.synaxis.command.builder.arguments.Argument#setSuggestionCallback(SuggestionCallback)}.
 * <p>
 * The engine calls it on the tick thread of the player asking for completions, while that player handles its
 * packets. The context holds the arguments before the cursor that parsed, default values included. The argument
 * being completed is in it only if what is typed of it parses. When nothing is typed of it yet, a text argument that
 * takes any word (string, word without a restriction, string array) is empty text, a string array then ending with
 * an empty word; read it with {@link CommandContext#get(String)} and check for null. The engine does not filter the
 * entries by what is already typed, the callback does.
 */
@FunctionalInterface
public interface SuggestionCallback {
    void apply(CommandSender sender, CommandContext context, Suggestion suggestion);
}
