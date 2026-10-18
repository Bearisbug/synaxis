package io.github.bearisbug.synaxis.command.builder;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

public class CommandResult {

    protected Type type = Type.UNKNOWN;
    protected String input;
    protected @Nullable ParsedCommand parsedCommand;
    protected @Nullable CommandData commandData;

    public Type getType() {
        return type;
    }

    public String getInput() {
        return input;
    }

    public @Nullable ParsedCommand getParsedCommand() {
        return parsedCommand;
    }

    public @Nullable CommandData getCommandData() {
        return commandData;
    }

    public enum Type {
        /**
         * An executor ran: that of a syntax, or the {@link Command#getDefaultExecutor() default executor} of the
         * command, also when the input is incomplete or matches no syntax.
         */
        SUCCESS,
        /**
         * The command exists, but no syntax matches the input and the command has no default executor; or an
         * argument with an {@link io.github.bearisbug.synaxis.command.builder.arguments.Argument#setCallback error
         * callback} failed, and that callback ran in place of any executor.
         */
        INVALID_SYNTAX,
        /**
         * The command did not run: a {@code PlayerCommandEvent} listener cancelled it, the condition of the command or
         * of the syntax refused the sender, or the executor or error callback threw, which the engine logs as an
         * ERROR.
         */
        CANCELLED,
        /**
         * Command is not registered, it is also the default result type.
         */
        UNKNOWN
    }

    public static CommandResult of(Type type, String input) {
        CommandResult result = new CommandResult();
        result.type = type;
        result.input = input;
        return result;
    }

    @ApiStatus.Internal
    public static CommandResult of(Type type, String input, ParsedCommand parsedCommand, @Nullable CommandData data) {
        CommandResult result = of(type, input);
        result.parsedCommand = parsedCommand;
        result.commandData = data;
        return result;
    }
}
