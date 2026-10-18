package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.ArgumentCallback;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.command.builder.CommandExecutor;
import io.github.bearisbug.synaxis.command.builder.CommandSyntax;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.condition.CommandCondition;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.PluginCommands;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The command capability of one plugin ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}).
 * <p>
 * The engine command cannot be renamed, so what the command manager holds is a host command named
 * {@code <plugin_id>:<name>} that carries the plugin command's condition, default executor,
 * syntaxes and subcommands as they are at {@link #register}. Their executors, conditions and
 * argument error callbacks are wrapped in place first: what the plugin's code throws there is
 * reported through one {@link LogBudget} per command instead of the engine's line per run, because
 * a client picks how often a command runs. A condition that throws counts as refused, which also
 * keeps it from failing the spawn of every player, whose commands are built while it spawns.
 * <p>
 * Checking that a bare name is free and taking it happen under the command manager's monitor, which
 * its own methods synchronise on as well, so two plugins cannot both take one.
 */
final class PluginCommandsImpl implements PluginCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginCommandsImpl.class);

    private final String pluginId;
    private final ServerProcess process;
    // Guarded by the command manager: the plugin's command → what is registered for it
    private final Map<Command, Command> registered = new HashMap<>();
    // Guarded by the command manager: set once the plugin is taken back
    private boolean closed;

    PluginCommandsImpl(String pluginId, ServerProcess process) {
        this.pluginId = pluginId;
        this.process = process;
    }

    @Override
    public void register(Command command) {
        for (String name : command.getNames()) {
            if (name.indexOf(':') >= 0)
                throw new IllegalArgumentException("a command name holds no ':', the host adds the namespace: " + name);
        }
        final CommandManager manager = process.command();
        final List<String> bare = new ArrayList<>();
        final List<String> givenUp = new ArrayList<>();
        final Command namespaced;
        synchronized (manager) {
            // A command registered once the plugin is taken back would stay with nothing to take it back
            if (closed) return;
            if (registered.containsKey(command))
                throw new IllegalStateException("plugin " + pluginId + " already registered " + command.getName());
            final List<String> aliases = new ArrayList<>();
            for (String name : command.getNames()) {
                if (!name.equals(command.getName())) aliases.add(pluginId + ":" + name);
                if (manager.commandExists(name)) givenUp.add(name);
                else bare.add(name);
            }
            aliases.addAll(bare);
            final LogBudget failures = new LogBudget(LOGGER, "failures");
            guard(command, pluginId + ":" + command.getName(), failures);
            namespaced = new Namespaced(pluginId + ":" + command.getName(), aliases.toArray(String[]::new), command,
                    pluginId, failures);
            manager.register(namespaced);
            registered.put(command, namespaced);
        }
        LOGGER.info("Registered command: plugin_id={} command={} bare_names={}{}", pluginId, namespaced.getName(), bare,
                givenUp.isEmpty() ? "" : " left_to_first_registrant=" + givenUp);
        refreshCompletions();
    }

    @Override
    public boolean unregister(Command command) {
        final CommandManager manager = process.command();
        synchronized (manager) {
            final Command namespaced = registered.remove(command);
            if (namespaced == null) return false;
            manager.unregister(namespaced);
        }
        refreshCompletions();
        return true;
    }

    /**
     * Takes back every command of the plugin, when it is disabled or failed to enable. Commands
     * registered afterwards are dropped.
     */
    void unregisterAll() {
        final CommandManager manager = process.command();
        synchronized (manager) {
            closed = true;
            if (registered.isEmpty()) return;
            for (Command namespaced : registered.values()) manager.unregister(namespaced);
            registered.clear();
        }
        refreshCompletions();
    }

    /** A player only learns which commands exist from the packet sent when it spawned. */
    private void refreshCompletions() {
        for (Player player : process.connection().getOnlinePlayers()) player.refreshCommands();
    }

    /**
     * @param name the name the command manager holds the plugin's command by, the one a failure is
     *             reported under
     */
    private void guard(Command command, String name, LogBudget failures) {
        command.setCondition(guarded(command.getCondition(), name, failures));
        final CommandExecutor defaultExecutor = command.getDefaultExecutor();
        if (defaultExecutor != null) command.setDefaultExecutor(guarded(defaultExecutor, failures));
        for (CommandSyntax syntax : command.getSyntaxes()) {
            syntax.setExecutor(guarded(syntax.getExecutor(), failures));
            syntax.setCommandCondition(guarded(syntax.getCommandCondition(), name, failures));
            for (Argument<?> argument : syntax.getArguments()) {
                final ArgumentCallback callback = argument.getCallback();
                if (callback != null && !(callback instanceof GuardedCallback))
                    argument.setCallback(new GuardedCallback(callback, name, pluginId, failures));
            }
        }
        for (Command subcommand : command.getSubcommands()) guard(subcommand, name, failures);
    }

    private CommandExecutor guarded(CommandExecutor executor, LogBudget failures) {
        if (executor instanceof Guarded) return executor;
        return new Guarded(executor, pluginId, failures);
    }

    private @Nullable CommandCondition guarded(@Nullable CommandCondition condition, String name, LogBudget failures) {
        if (condition == null || condition instanceof GuardedCondition) return condition;
        return new GuardedCondition(condition, name, pluginId, failures);
    }

    /**
     * An executor of a plugin whose exception ends here, in the plugin's budget, with the sender
     * told the command failed.
     */
    private record Guarded(CommandExecutor executor, String pluginId, LogBudget failures) implements CommandExecutor {
        @Override
        public void apply(CommandSender sender, CommandContext context) {
            run(() -> executor.apply(sender, context), sender, context.getInput(), pluginId, failures);
        }
    }

    /**
     * A condition of a plugin whose exception ends here, in the plugin's budget, as a refusal.
     */
    private record GuardedCondition(CommandCondition condition, String name, String pluginId, LogBudget failures)
            implements CommandCondition {
        @Override
        public boolean canUse(CommandSender sender, @Nullable String commandString) {
            try {
                return condition.canUse(sender, commandString);
            } catch (RuntimeException e) {
                failures.error(e, "Plugin command condition failed: plugin_id={} command={}", pluginId, name);
                return false;
            }
        }
    }

    /**
     * The error callback of a plugin's argument, its exception ending as that of an executor.
     */
    private record GuardedCallback(ArgumentCallback callback, String name, String pluginId, LogBudget failures)
            implements ArgumentCallback {
        @Override
        public void apply(CommandSender sender, ArgumentSyntaxException exception) {
            run(() -> callback.apply(sender, exception), sender, name, pluginId, failures);
        }
    }

    private static void run(Runnable call, CommandSender sender, String input, String pluginId, LogBudget failures) {
        try {
            call.run();
        } catch (RuntimeException e) {
            failures.error(e, "Plugin command failed: plugin_id={} command={}", pluginId, input);
            sender.sendMessage(Component.translatable("command.failed",
                    "An unexpected error occurred trying to execute that command"));
        }
    }

    /** What the command manager holds for a plugin command. */
    private static final class Namespaced extends Command {
        private final Command command;
        private final String pluginId;
        private final LogBudget failures;

        Namespaced(String name, String[] aliases, Command command, String pluginId, LogBudget failures) {
            super(name, aliases);
            this.command = command;
            this.pluginId = pluginId;
            this.failures = failures;
            setCondition(command.getCondition());
            setDefaultExecutor(command.getDefaultExecutor());
            getSyntaxes().addAll(command.getSyntaxes());
            for (Command subcommand : command.getSubcommands()) addSubcommand(subcommand);
        }

        @Override
        public void globalListener(CommandSender sender, @UnknownNullability CommandContext context, String input) {
            run(() -> command.globalListener(sender, context, input), sender, input, pluginId, failures);
        }
    }
}
