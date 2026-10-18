package io.github.bearisbug.synaxis.command.builder.condition;

import io.github.bearisbug.synaxis.command.CommandSender;
import org.jetbrains.annotations.Nullable;

/**
 * Used to know if the {@link CommandSender} is allowed to run the command or a specific syntax.
 */
@FunctionalInterface
public interface CommandCondition {

    /**
     * Called when the sender permission needs to be checked.
     * <p>
     * The first time will be during player connection in order to know
     * if the command/syntax should be displayed as tab-completion suggestion,
     * {@code commandString} will be null in this case. (It is also possible for the command string
     * to be null if a new command packet is built)
     * <p>
     * That first call happens while the player spawns, before it has an instance:
     * {@code getInstance()} is null then. A packet is built again on the thread that calls
     * {@code Player#refreshCommands}, which is meant to be the player's tick thread. Conditions of
     * different players can then be asked at once, so a condition reads only what is safe to read from
     * any tick thread. A condition that throws while a packet is built hides the command or syntax
     * from that player, with one WARN line per throw.
     * <p>
     * Otherwise, {@code commandString} will never be null
     * but will instead be the raw command string given by the sender.
     * You should in this case warn the sender (eg by sending a message) if the condition is unsuccessful.
     *
     * @param sender        the sender of the command
     * @param commandString the raw command string,
     *                      null if this is an access request
     * @return true if the sender has the right to use the command, false otherwise
     */
    boolean canUse(CommandSender sender, @Nullable String commandString);
}
