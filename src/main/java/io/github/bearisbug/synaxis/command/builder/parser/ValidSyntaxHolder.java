package io.github.bearisbug.synaxis.command.builder.parser;

import io.github.bearisbug.synaxis.command.builder.CommandSyntax;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;

import java.util.Map;

/**
 * Holds the data of a validated syntax.
 */
public record ValidSyntaxHolder(String commandString,
                                CommandSyntax syntax,
                                Map<Argument<?>, ArgumentParser.ArgumentResult> argumentResults) {

}
