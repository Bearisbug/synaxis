package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.command.builder.Command;
import org.jetbrains.annotations.ApiStatus;

/**
 * The commands of one plugin ({@code docs/DESIGN-PROD.md} API-PROD-011).
 * <p>
 * A command registered as {@code party} by the plugin {@code mmo} is registered as
 * {@code mmo:party}, and also as {@code party} unless another command already answers to that name:
 * a bare name belongs to whoever registered it first. The same goes for every alias of the command.
 * The host takes every command of a plugin back when the plugin is disabled.
 * <p>
 * Who may run a command is the command's own {@link Command#setCondition condition}, which also
 * decides whether a player sees it among the completions; check {@link PluginContext#permissions()}
 * there, never {@code Player#getPermissionLevel()}. An executor runs on the tick thread of the
 * player that sent the command, and on the console reader thread for the console. What an executor
 * throws is logged by the host at most once every 10 s per command, and the sender is told the
 * command failed.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginCommands {

    /**
     * Registers a command under this plugin's namespace.
     *
     * @param command a command whose name and aliases hold no {@code :}
     * @throws IllegalArgumentException if a name or an alias holds {@code :}
     * @throws IllegalStateException    if this plugin already registered a command under one of its names
     */
    void register(Command command);

    /**
     * Takes back a command this plugin registered, with every name it got.
     *
     * @param command the command passed to {@link #register}
     * @return false if this plugin did not register it, in which case nothing changes
     */
    boolean unregister(Command command);
}
