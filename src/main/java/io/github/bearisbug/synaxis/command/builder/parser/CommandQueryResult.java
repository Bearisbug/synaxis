package io.github.bearisbug.synaxis.command.builder.parser;

import io.github.bearisbug.synaxis.command.builder.Command;

import java.util.List;

public record CommandQueryResult(List<Command> parents,
                                 Command command,
                                 String commandName,
                                 String[] args) {
}
