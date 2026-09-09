package dev.gameengine.command.builder.parser;

import dev.gameengine.command.builder.CommandSyntax;
import dev.gameengine.command.builder.exception.ArgumentSyntaxException;

/**
 * Holds the data of an invalidated syntax.
 */
public record CommandSuggestionHolder(CommandSyntax syntax,
                                      ArgumentSyntaxException argumentSyntaxException,
                                      int argIndex) {
}
