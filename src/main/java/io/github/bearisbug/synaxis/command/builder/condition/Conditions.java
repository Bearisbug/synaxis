package io.github.bearisbug.synaxis.command.builder.condition;

import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Common command conditions
 */
public final class Conditions {
    /**
     * Will only execute if all command conditions succeed.
     */
    public static CommandCondition all(CommandCondition... conditions) {
        Objects.requireNonNull(conditions, "conditions cannot be null");
        for (CommandCondition condition : conditions) {
            Objects.requireNonNull(condition, "condition cannot be null");
        }
        return (sender, commandString) -> {
            for (CommandCondition condition : conditions) {
                if (!condition.canUse(sender, commandString)) {
                    return false;
                }
            }

            return true;
        };
    }

    /**
     * Will execute if one or more command conditions succeed.
     */
    public static CommandCondition any(CommandCondition... conditions) {
        Objects.requireNonNull(conditions, "conditions cannot be null");
        for (CommandCondition condition : conditions) {
            Objects.requireNonNull(condition, "condition cannot be null");
        }
        return (sender, commandString) -> {
            for (CommandCondition condition : conditions) {
                if (condition.canUse(sender, commandString)) {
                    return true;
                }
            }

            return false;
        };
    }

    /**
     * Will succeed if the command sender is a player.
     */
    public static boolean playerOnly(CommandSender sender, @Nullable String commandString) {
        return sender instanceof Player;
    }

    /**
     * Will succeed if the command sender is the server console.
     */
    public static boolean consoleOnly(CommandSender sender, @Nullable String commandString) {
        return sender instanceof ConsoleSender;
    }

    /**
     * Inverts the result of the given condition.
     */
    public static CommandCondition not(CommandCondition condition) {
        Objects.requireNonNull(condition, "condition cannot be null");
        return (sender, commandString) -> !condition.canUse(sender, commandString);
    }
}
