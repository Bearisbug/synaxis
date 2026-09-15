package io.github.bearisbug.synaxis.command.builder.parser;

import io.github.bearisbug.synaxis.command.builder.CommandSyntax;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;

/**
 * Holds the data of an invalidated syntax.
 */
public record CommandSuggestionHolder(CommandSyntax syntax,
                                      ArgumentSyntaxException argumentSyntaxException,
                                      int argIndex) {
}
