package dev.gameengine.command.builder.parser;

import dev.gameengine.command.builder.CommandSyntax;
import dev.gameengine.command.builder.arguments.Argument;

import java.util.Map;

/**
 * Holds the data of a validated syntax.
 */
public record ValidSyntaxHolder(String commandString,
                                CommandSyntax syntax,
                                Map<Argument<?>, ArgumentParser.ArgumentResult> argumentResults) {

}
