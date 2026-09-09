package dev.gameengine.command.builder.parser;

import dev.gameengine.command.builder.Command;

import java.util.List;

public record CommandQueryResult(List<Command> parents,
                                 Command command,
                                 String commandName,
                                 String[] args) {
}
