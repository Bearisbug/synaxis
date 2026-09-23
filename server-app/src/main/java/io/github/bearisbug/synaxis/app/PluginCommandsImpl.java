package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.command.builder.CommandExecutor;
import io.github.bearisbug.synaxis.command.builder.CommandSyntax;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.PluginCommands;
import net.kyori.adventure.text.Component;
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
 * syntaxes and subcommands as they are at {@link #register}. Their executors are wrapped in place
 * first: a plugin executor that throws is reported through one {@link LogBudget} per command
 * instead of the engine's line per run, because a client picks how often a command runs.
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
            guard(command, failures);
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
     * Takes back every command of the plugin, when it is disabled or failed to enable.
     */
    void unregisterAll() {
        final CommandManager manager = process.command();
        synchronized (manager) {
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

    private void guard(Command command, LogBudget failures) {
        final CommandExecutor defaultExecutor = command.getDefaultExecutor();
        if (defaultExecutor != null) command.setDefaultExecutor(guarded(defaultExecutor, failures));
        for (CommandSyntax syntax : command.getSyntaxes()) syntax.setExecutor(guarded(syntax.getExecutor(), failures));
        for (Command subcommand : command.getSubcommands()) guard(subcommand, failures);
    }

    private CommandExecutor guarded(CommandExecutor executor, LogBudget failures) {
        if (executor instanceof Guarded) return executor;
        return new Guarded(executor, pluginId, failures);
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
