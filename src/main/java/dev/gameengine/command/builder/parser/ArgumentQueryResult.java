package dev.gameengine.command.builder.parser;

import dev.gameengine.command.builder.CommandContext;
import dev.gameengine.command.builder.CommandSyntax;
import dev.gameengine.command.builder.arguments.Argument;

public record ArgumentQueryResult(CommandSyntax syntax,
                                  Argument<?> argument,
                                  CommandContext context,
                                  String input) {
}
