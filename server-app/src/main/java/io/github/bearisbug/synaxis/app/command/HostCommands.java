package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.command.CommandManager;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentStringArray;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentWord;
import io.github.bearisbug.synaxis.command.builder.arguments.number.ArgumentInteger;
import io.github.bearisbug.synaxis.command.builder.suggestion.SuggestionEntry;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.server.ServerTickMonitorEvent;
import io.github.bearisbug.synaxis.plugin.Permissions;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The seven commands the host brings along ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-012},
 * {@code REQ-PROD-034}, {@code API-PROD-011}): {@code list}, {@code kick <player> [reason]},
 * {@code broadcast <text>}, {@code save}, {@code stop [seconds]}, {@code tps} and
 * {@code profile [seconds]}.
 * <p>
 * The console may run all of them. A player runs one other than {@code list} only while holding
 * {@code synaxis.command.<name>}; without it the engine refuses the command as a failed
 * precondition and leaves it out of the player's completions. {@code list} asks for no node, as the
 * vanilla {@code /list} asks for permission level 0. Every run writes one {@code gm_command} event
 * except a player's {@code list}, which is no GM command. A player's command the engine does not
 * know is answered with one line.
 */
public final class HostCommands {

    /** A player runs the command {@code <name>} while holding this prefix followed by the name. */
    public static final String NODE_PREFIX = "synaxis.command.";
    /** How long the shutdown waits for a stop that is still answering, see {@link #awaitStopAnswers()}. */
    static final Duration ANSWER_WAIT = Duration.ofSeconds(1);

    private final ServerProcess process;
    private final Permissions permissions;
    private final PlayerStore store;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final Profiler profiler;
    private final TickStats ticks = new TickStats();
    /**
     * Taken by a stop before it asks the engine and given back once it has answered, so that the
     * answer and its ledger line come before the ledger and the management connections close.
     */
    private final Semaphore answering = new Semaphore(1);

    /**
     * @param process     the engine process
     * @param permissions the permission nodes of the players
     * @param store       the player store, which {@code save} saves
     * @param analytics   the event ledger
     * @param profiler    the one recording of the process, started by the command of that name
     */
    public HostCommands(ServerProcess process, Permissions permissions, PlayerStore store,
                        BiConsumer<String, Map<String, ?>> analytics, Profiler profiler) {
        this.process = process;
        this.permissions = permissions;
        this.store = store;
        this.analytics = analytics;
        this.profiler = profiler;
    }

    /**
     * Registers the seven commands and the answer to an unknown command, and starts counting the
     * ticks {@code tps} reports on. Called before the plugins are enabled, so that the bare names are
     * the host's.
     */
    public void register() {
        process.eventHandler().addListener(ServerTickMonitorEvent.class, ticks::record);
        process.command().register(list(), kick(), broadcast(), save(), stop(), tps(), profile());
        process.command().setUnknownCommandCallback(HostCommands::unknown);
    }

    /**
     * Asks the engine to shut down ({@code API-PROD-011}). The console, a player's {@code stop} and
     * the management method {@code minecraft:server/stop} all come through here, and only the
     * engine says whether this call started the shutdown: the host keeps no flag of its own, which
     * a signal could get ahead of. Takes the answering permit first; whatever the engine said, the
     * caller answers and then calls {@link #stopAnswered()}.
     *
     * @param countdown how long the gate stays closed before the save
     * @return true if this call started the shutdown, false if one had already started, from a
     * signal, the watchdog or an earlier stop
     */
    public boolean beginStop(Duration countdown) {
        answering.acquireUninterruptibly();
        return process.beginShutdown(countdown);
    }

    /**
     * Gives back the permit {@link #beginStop} took, once the caller has answered.
     */
    public void stopAnswered() {
        answering.release();
    }

    /**
     * A shutdown task ahead of the ledger's and the management interface's: waits, at most
     * {@link #ANSWER_WAIT}, for a stop that is still answering.
     */
    public void awaitStopAnswers() {
        try {
            if (answering.tryAcquire(ANSWER_WAIT.toNanos(), TimeUnit.NANOSECONDS)) answering.release();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the last ticks, as {@code tps} reports them.
     *
     * @return the window of {@code tps}
     */
    public TickStats.Snapshot ticks() {
        return ticks.snapshot();
    }

    /**
     * Returns the one recording of the process, shared with the management interface.
     *
     * @return the recording
     */
    public Profiler profiler() {
        return profiler;
    }

    private Command command(String name) {
        final Command command = new Command(name);
        final String node = NODE_PREFIX + name;
        command.setCondition((sender, _) -> !(sender instanceof Player player) || permissions.has(player, node));
        return command;
    }

    private Command list() {
        // No condition: every player runs it and sees it among the completions
        final Command list = new Command("list");
        list.setDefaultExecutor((sender, context) -> {
            final List<String> names = process.connection().getOnlinePlayers().stream()
                    .map(Player::getUsername).sorted().toList();
            reply(sender, "synaxis:command-list", "%s players online: %s", String.valueOf(names.size()),
                    String.join(", ", names));
            // A player's list is no GM command; recording it would let the client fill the ledger
            if (!(sender instanceof Player)) record(sender, context.getInput(), null, "ok");
        });
        return list;
    }

    /**
     * Answers a player whose command has a root the engine does not know, naming its first word
     * without a leading slash. The console gets nothing: {@code Main#runConsoleCommand} already logs
     * the line. Neither logs nor writes to the ledger, as the client decides how often it comes.
     */
    private static void unknown(CommandSender sender, String input) {
        if (!(sender instanceof Player)) return;
        final String command = input.startsWith(CommandManager.COMMAND_PREFIX)
                ? input.substring(CommandManager.COMMAND_PREFIX.length()) : input;
        final int space = command.indexOf(' ');
        reply(sender, "synaxis:command-unknown", "Unknown command: %s",
                space < 0 ? command : command.substring(0, space));
    }

    private Command kick() {
        final Command kick = command("kick");
        final ArgumentWord target = ArgumentType.Word("player");
        target.setSuggestionCallback((_, context, suggestion) -> {
            final String typed = context.get(target);
            final String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
            for (Player player : process.connection().getOnlinePlayers()) {
                if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix))
                    suggestion.addEntry(new SuggestionEntry(player.getUsername()));
            }
        });
        final ArgumentStringArray reason = ArgumentType.StringArray("reason");
        kick.addSyntax((sender, context) -> kick(sender, context.getInput(), context.get(target), null), target);
        kick.addSyntax((sender, context) -> kick(sender, context.getInput(), context.get(target),
                String.join(" ", context.get(reason))), target, reason);
        return kick;
    }

    private void kick(CommandSender sender, String input, String name, @Nullable String reason) {
        final Player player = process.connection().getOnlinePlayerByUsername(name);
        if (player == null) {
            reply(sender, "synaxis:command-no-player", "No player online here is called %s", name);
            record(sender, input, null, "no_target");
            return;
        }
        player.kick(reason == null ? Component.translatable("multiplayer.disconnect.kicked", "Kicked by an operator")
                : Component.text(reason));
        reply(sender, "synaxis:command-kicked", "Kicked %s", player.getUsername());
        record(sender, input, player.getUuid(), "ok");
    }

    private Command broadcast() {
        final Command broadcast = command("broadcast");
        final ArgumentStringArray text = ArgumentType.StringArray("text");
        broadcast.addSyntax((sender, context) -> {
            final Component message = Component.text(String.join(" ", context.get(text)));
            int players = 0;
            for (Player player : process.connection().getOnlinePlayers()) {
                player.sendMessage(message);
                players++;
            }
            reply(sender, "synaxis:command-broadcast", "Sent to %s players", String.valueOf(players));
            record(sender, context.getInput(), null, "ok");
        }, text);
        return broadcast;
    }

    private Command save() {
        final Command save = command("save");
        save.setDefaultExecutor((sender, context) -> {
            // Answered once the writes are over, on whichever thread finishes the last of them
            var _ = store.saveOnline().thenAccept(count -> {
                reply(sender, "synaxis:command-saved", "Saved %s of %s players", String.valueOf(count.saved()),
                        String.valueOf(count.online()));
                record(sender, context.getInput(), null, count.saved() == count.online() ? "ok" : "partial");
            });
        });
        return save;
    }

    private Command stop() {
        final Command stop = command("stop");
        final ArgumentInteger seconds = ArgumentType.Integer("seconds");
        seconds.min(0);
        stop.setDefaultExecutor((sender, context) -> {
            // The engine also runs the default executor for an argument that does not parse, and
            // "stop -1" must not stop at once
            if (context.getInput().strip().indexOf(' ') >= 0) {
                reply(sender, "synaxis:command-stop-usage", "Usage: stop [seconds], a whole number from 0");
                record(sender, context.getInput(), null, "invalid");
                return;
            }
            stop(sender, context.getInput(), 0);
        });
        stop.addSyntax((sender, context) -> stop(sender, context.getInput(), context.get(seconds)), seconds);
        return stop;
    }

    private void stop(CommandSender sender, String input, int seconds) {
        final boolean started = beginStop(Duration.ofSeconds(seconds));
        try {
            if (!started) {
                reply(sender, "synaxis:command-already-stopping", "The server is already stopping");
                record(sender, input, null, "invalid");
                return;
            }
            reply(sender, "synaxis:command-stopping", "The server stops in %s s", String.valueOf(seconds));
            record(sender, input, null, "ok");
        } finally {
            stopAnswered();
        }
    }

    private Command profile() {
        final Command profile = command("profile");
        final ArgumentInteger seconds = ArgumentType.Integer("seconds");
        seconds.between(Profiler.MIN_SECONDS, Profiler.MAX_SECONDS);
        profile.setDefaultExecutor((sender, context) -> {
            // As for stop: the engine runs the default executor for a length it could not take
            if (context.getInput().strip().indexOf(' ') >= 0) {
                reply(sender, "synaxis:command-profile-usage", "Usage: profile [seconds], a whole number from %s to %s",
                        String.valueOf(Profiler.MIN_SECONDS), String.valueOf(Profiler.MAX_SECONDS));
                record(sender, context.getInput(), null, "invalid");
                return;
            }
            profile(sender, context.getInput(), Profiler.DEFAULT_SECONDS);
        });
        profile.addSyntax((sender, context) -> profile(sender, context.getInput(), context.get(seconds)), seconds);
        return profile;
    }

    private void profile(CommandSender sender, String input, int seconds) {
        final Profiler.Start start;
        try {
            start = profiler.start(seconds);
        } catch (IOException e) {
            reply(sender, "synaxis:command-profile-failed", "The recording failed: %s", String.valueOf(e.getMessage()));
            record(sender, input, null, "unavailable");
            return;
        }
        switch (start) {
            case Profiler.Busy busy -> {
                reply(sender, "synaxis:command-profile-busy", "A recording to %s is still running",
                        busy.path().toString());
                record(sender, input, null, "invalid");
            }
            case Profiler.Started started -> {
                reply(sender, "synaxis:command-profile-started", "Recording %s s to %s", String.valueOf(seconds),
                        started.path().toString());
                // Answered again on the recording's own thread once the file is written
                var _ = started.written().whenComplete((path, failure) -> {
                    if (failure == null) {
                        reply(sender, "synaxis:command-profile-done", "Recorded %s", path.toString());
                        record(sender, input, null, "ok");
                    } else {
                        reply(sender, "synaxis:command-profile-failed", "The recording failed: %s",
                                String.valueOf(failure.getMessage()));
                        record(sender, input, null, "unavailable");
                    }
                });
            }
        }
    }

    private Command tps() {
        final Command tps = command("tps");
        tps.setDefaultExecutor((sender, context) -> {
            final TickStats.Snapshot snapshot = ticks.snapshot();
            reply(sender, "synaxis:command-tps", "TPS %s, MSPT p50 %s ms, p95 %s ms, p99 %s ms over the last %s ticks",
                    decimal(snapshot.tps()), decimal(snapshot.p50()), decimal(snapshot.p95()), decimal(snapshot.p99()),
                    String.valueOf(snapshot.ticks()));
            record(sender, context.getInput(), null, "ok");
        });
        return tps;
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void reply(CommandSender sender, String key, String fallback, String... arguments) {
        final Component[] components = new Component[arguments.length];
        for (int i = 0; i < arguments.length; i++) components[i] = Component.text(arguments[i]);
        sender.sendMessage(Component.translatable(key, fallback, components));
    }

    private void record(CommandSender sender, String input, @Nullable UUID target, String result) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("actor", sender instanceof Player ? "player" : "console");
        attributes.put("actor_uuid", sender instanceof Player player ? player.getUuid() : null);
        attributes.put("command", input);
        attributes.put("target", target);
        attributes.put("result", result);
        analytics.accept("gm_command", attributes);
    }
}
