package io.github.bearisbug.synaxis.app.moderation;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentStringArray;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentWord;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.Ban;
import io.github.bearisbug.synaxis.plugin.BanTarget;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.bearisbug.synaxis.app.command.GmCommands.record;
import static io.github.bearisbug.synaxis.app.command.GmCommands.reply;

/**
 * The GM commands of bans and the whitelist ({@code docs/DESIGN-PROD.md} {@code API-PROD-009}, §10
 * nodes {@value #BAN_NODE} and {@value #WHITELIST_NODE}):
 * <ul>
 *     <li>{@code ban <player|uuid|address[/prefix]> <perm|<n>m|<n>h|<n>d> <reason…>}</li>
 *     <li>{@code unban <ban_id>}</li>
 *     <li>{@code whitelist add|remove <player|uuid>}</li>
 * </ul>
 * A name is looked up among the players online on this shard; an account elsewhere, or one that has
 * never logged in, is named by its uuid. Only the console and a player holding the node can run
 * them; for anybody else the engine refuses them as a failed precondition. The database is reached
 * on a virtual thread, never on the thread that runs the command. Every run writes one
 * {@code gm_command} event, a ban also its {@code player_banned}.
 */
public final class ModerationCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModerationCommands.class);

    /** The node that lets a player ban and lift bans. */
    public static final String BAN_NODE = "gm.ban";
    /** The node that lets a player change the whitelist. */
    public static final String WHITELIST_NODE = "gm.whitelist";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]{0,5})([mhd])");

    private final ServerProcess process;
    private final HostModeration moderation;
    private final Permissions permissions;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final LogBudget failures = new LogBudget(LOGGER, "moderation command failures");

    /**
     * @param process     the engine process, whose online players a name is looked up among
     * @param moderation  the bans and the whitelist of this shard
     * @param permissions who holds the nodes
     * @param analytics   the event ledger
     */
    public ModerationCommands(ServerProcess process, HostModeration moderation, Permissions permissions,
                              BiConsumer<String, Map<String, ?>> analytics) {
        this.process = process;
        this.moderation = moderation;
        this.permissions = permissions;
        this.analytics = analytics;
    }

    /**
     * The three commands, to register with the command manager.
     *
     * @return {@code ban}, {@code unban} and {@code whitelist}
     */
    public List<Command> commands() {
        final Command ban = guarded("ban", BAN_NODE);
        final ArgumentWord target = ArgumentType.Word("target");
        final ArgumentWord duration = ArgumentType.Word("duration");
        final ArgumentStringArray reason = ArgumentType.StringArray("reason");
        ban.addSyntax((sender, context) -> run(sender, context.getInput(),
                () -> ban(sender, context.get(target), context.get(duration), String.join(" ", context.get(reason)))),
                target, duration, reason);

        final Command unban = guarded("unban", BAN_NODE);
        final ArgumentWord banId = ArgumentType.Word("ban_id");
        unban.addSyntax((sender, context) -> run(sender, context.getInput(), () -> unban(sender, context.get(banId))),
                banId);

        final Command whitelist = guarded("whitelist", WHITELIST_NODE);
        final ArgumentWord account = ArgumentType.Word("account");
        whitelist.addSyntax((sender, context) -> run(sender, context.getInput(),
                () -> whitelist(sender, context.get(account), true)), ArgumentType.Literal("add"), account);
        whitelist.addSyntax((sender, context) -> run(sender, context.getInput(),
                () -> whitelist(sender, context.get(account), false)), ArgumentType.Literal("remove"), account);
        return List.of(ban, unban, whitelist);
    }

    private Command guarded(String name, String node) {
        final Command command = new Command(name);
        command.setCondition((sender, _) -> !(sender instanceof Player player) || permissions.has(player, node));
        return command;
    }

    /** What one run did: its result for the event, and the account or network it acted on. */
    private record Outcome(String result, @Nullable String target) {
    }

    @FunctionalInterface
    private interface Action {
        Outcome run();
    }

    private void run(CommandSender sender, String input, Action action) {
        Thread.ofVirtual().name("Syn-Moderation-Command").start(() -> {
            Outcome outcome;
            try {
                outcome = action.run();
            } catch (CompletionException e) {
                failures.warn(e.getCause(), "A moderation command failed: command={}", StringUtils.escapeForLog(input));
                reply(sender, "synaxis:moderation-unavailable", "Bans cannot be reached right now");
                outcome = new Outcome("unavailable", null);
            }
            record(analytics, sender, input, outcome.target(), outcome.result());
        });
    }

    private Outcome ban(CommandSender sender, String targetText, String durationText, String reason) {
        final BanTarget target = target(targetText);
        if (target == null) {
            reply(sender, "synaxis:moderation-no-target", "No online player, uuid or address %s", targetText);
            return new Outcome("no_target", null);
        }
        final String subject = HostModeration.subject(target);
        final Instant expiresAt;
        if ("perm".equals(durationText)) expiresAt = null;
        else {
            final Matcher matcher = DURATION.matcher(durationText);
            if (!matcher.matches()) {
                reply(sender, "synaxis:moderation-bad-duration", "%s is not perm, <n>m, <n>h or <n>d", durationText);
                return new Outcome("bad_duration", subject);
            }
            final long amount = Long.parseLong(matcher.group(1));
            expiresAt = Instant.now().plus(switch (matcher.group(2)) {
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            });
        }
        final int length = reason.codePointCount(0, reason.length());
        if (length == 0 || length > HostModeration.MAX_REASON) {
            reply(sender, "synaxis:moderation-bad-reason", "A reason has 1 to %s characters",
                    String.valueOf(HostModeration.MAX_REASON));
            return new Outcome("bad_reason", subject);
        }
        if (!moderation.hasDatabase()) {
            reply(sender, "synaxis:moderation-unavailable", "Bans cannot be reached right now");
            return new Outcome("unavailable", subject);
        }
        final Ban ban = moderation.ban(target, reason, expiresAt, operator(sender)).join();
        reply(sender, "synaxis:ban-done", "Banned %s, ban %s", targetText, String.valueOf(ban.banId()));
        return new Outcome("ok", subject);
    }

    private Outcome unban(CommandSender sender, String banIdText) {
        final long banId;
        try {
            banId = Long.parseLong(banIdText);
        } catch (NumberFormatException _) {
            reply(sender, "synaxis:unban-none", "There is no ban %s in force", banIdText);
            return new Outcome("no_ban", null);
        }
        if (!moderation.hasDatabase()) {
            reply(sender, "synaxis:moderation-unavailable", "Bans cannot be reached right now");
            return new Outcome("unavailable", banIdText);
        }
        if (!moderation.unban(banId, operator(sender)).join()) {
            reply(sender, "synaxis:unban-none", "There is no ban %s in force", banIdText);
            return new Outcome("no_ban", banIdText);
        }
        reply(sender, "synaxis:unban-done", "Lifted ban %s", banIdText);
        return new Outcome("ok", banIdText);
    }

    private Outcome whitelist(CommandSender sender, String accountText, boolean listed) {
        final UUID account = account(accountText);
        if (account == null) {
            reply(sender, "synaxis:moderation-no-target", "No online player, uuid or address %s", accountText);
            return new Outcome("no_target", null);
        }
        if (!moderation.hasDatabase()) {
            reply(sender, "synaxis:moderation-unavailable", "Bans cannot be reached right now");
            return new Outcome("unavailable", account.toString());
        }
        moderation.whitelist(account, listed).join();
        if (listed) reply(sender, "synaxis:whitelist-added", "Put %s on the whitelist", accountText);
        else reply(sender, "synaxis:whitelist-removed", "Took %s off the whitelist", accountText);
        return new Outcome("ok", account.toString());
    }

    private @Nullable BanTarget target(String text) {
        // A Minecraft name holds neither a dot nor a colon, an address always one of them
        if (text.contains(".") || text.contains(":")) {
            try {
                return new BanTarget.ByNetwork(text);
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
        final UUID account = account(text);
        return account == null ? null : new BanTarget.ByUuid(account);
    }

    private @Nullable UUID account(String text) {
        if (UUID_TEXT.matcher(text).matches()) return UUID.fromString(text);
        final Player online = process.connection().getOnlinePlayerByUsername(text);
        return online == null ? null : online.getUuid();
    }

    private static UUID operator(CommandSender sender) {
        return sender instanceof Player player ? player.getUuid() : HostModeration.CONSOLE;
    }
}
