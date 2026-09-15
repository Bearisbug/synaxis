package io.github.bearisbug.synaxis.command.builder.parser;

import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.command.builder.CommandSyntax;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;

public record ArgumentQueryResult(CommandSyntax syntax,
                                  Argument<?> argument,
                                  CommandContext context,
                                  String input) {
}
