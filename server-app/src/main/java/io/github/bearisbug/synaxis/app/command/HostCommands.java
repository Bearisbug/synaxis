package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The six commands the host brings along ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-012},
 * {@code API-PROD-011}): {@code list}, {@code kick <player> [reason]}, {@code broadcast <text>},
 * {@code save}, {@code stop [seconds]} and {@code tps}.
 * <p>
 * The console may run all of them. A player runs one only while holding
 * {@code synaxis.command.<name>}; without it the engine refuses the command as a failed
 * precondition and leaves it out of the player's completions. Every run writes one
 * {@code gm_command} event.
 */
public final class HostCommands {

    /** A player runs the command {@code <name>} while holding this prefix followed by the name. */
    public static final String NODE_PREFIX = "synaxis.command.";

    private final ServerProcess process;
    private final Permissions permissions;
    private final PlayerStore store;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final TickStats ticks = new TickStats();

    /**
     * @param process     the engine process
     * @param permissions the permission nodes of the players
     * @param store       the player store, which {@code save} saves
     * @param analytics   the event ledger
     */
    public HostCommands(ServerProcess process, Permissions permissions, PlayerStore store,
                        BiConsumer<String, Map<String, ?>> analytics) {
        this.process = process;
        this.permissions = permissions;
        this.store = store;
        this.analytics = analytics;
    }

    /**
     * Registers the six commands and starts counting the ticks {@code tps} reports on. Called
     * before the plugins are enabled, so that the bare names are the host's.
     */
    public void register() {
        process.eventHandler().addListener(ServerTickMonitorEvent.class, ticks::record);
        process.command().register(list(), kick(), broadcast(), save(), stop(), tps());
    }

    private Command command(String name) {
        final Command command = new Command(name);
        final String node = NODE_PREFIX + name;
        command.setCondition((sender, _) -> !(sender instanceof Player player) || permissions.has(player, node));
        return command;
    }

    private Command list() {
        final Command list = command("list");
        list.setDefaultExecutor((sender, context) -> {
            final List<String> names = process.connection().getOnlinePlayers().stream()
                    .map(Player::getUsername).sorted().toList();
            reply(sender, "synaxis:command-list", "%s players online: %s", String.valueOf(names.size()),
                    String.join(", ", names));
            record(sender, context.getInput(), null, "ok");
        });
        return list;
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
        reply(sender, "synaxis:command-stopping", "The server stops in %s s", String.valueOf(seconds));
        record(sender, input, null, "ok");
        // Its own thread, not a daemon: the sequence waits for every tick thread, the one running a
        // player's command included, and the JVM exits once it is over and this thread with it
        Thread.ofPlatform().name("server-app-stop").start(() -> process.beginShutdown(Duration.ofSeconds(seconds)));
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
