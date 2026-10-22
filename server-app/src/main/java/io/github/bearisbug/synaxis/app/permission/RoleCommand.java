package io.github.bearisbug.synaxis.app.permission;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentWord;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static io.github.bearisbug.synaxis.app.command.GmCommands.record;
import static io.github.bearisbug.synaxis.app.command.GmCommands.reply;

/**
 * The GM command that grants and takes back roles ({@code docs/DESIGN-PROD.md} {@code API-PROD-010},
 * §10 node {@code gm.role}): {@code role grant|revoke <player|uuid> <role>} and
 * {@code role list <player|uuid>}. A name is looked up among the players online on this shard; an
 * account elsewhere, or one that has never logged in, is named by its uuid.
 * <p>
 * Only the console and a player holding {@value #NODE} can run it; for anybody else the engine
 * refuses it as a failed precondition and leaves it out of the completions. A player grants or
 * takes back only a role whose every node its own nodes cover, as the database has them when it
 * asks, and what it grants runs out no later than its own grants; the console any role, for good.
 * The database is reached on a virtual thread, never on the thread that runs the command. Every run
 * writes one {@code gm_command} event.
 */
public final class RoleCommand extends Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoleCommand.class);

    /** The node that lets a player run this command. */
    public static final String NODE = "gm.role";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ServerProcess process;
    private final HostPermissions permissions;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final LogBudget failures = new LogBudget(LOGGER, "role command failures");

    /**
     * @param process     the engine process, whose online players a name is looked up among
     * @param permissions the roles of this shard
     * @param analytics   the event ledger
     */
    public RoleCommand(ServerProcess process, HostPermissions permissions, BiConsumer<String, Map<String, ?>> analytics) {
        super("role");
        this.process = process;
        this.permissions = permissions;
        this.analytics = analytics;
        setCondition((sender, _) -> !(sender instanceof Player player) || permissions.has(player, NODE));
        final ArgumentWord target = ArgumentType.Word("target");
        final ArgumentWord role = ArgumentType.Word("role");
        addSyntax((sender, context) -> run(sender, context.getInput(), "grant", context.get(target), context.get(role)),
                ArgumentType.Literal("grant"), target, role);
        addSyntax((sender, context) -> run(sender, context.getInput(), "revoke", context.get(target), context.get(role)),
                ArgumentType.Literal("revoke"), target, role);
        addSyntax((sender, context) -> run(sender, context.getInput(), "list", context.get(target), null),
                ArgumentType.Literal("list"), target);
    }

    private void run(CommandSender sender, String input, String action, String targetText, @Nullable String role) {
        Thread.ofVirtual().name("Syn-Role-Command").start(() -> {
            final UUID target = resolve(targetText);
            final String result = target == null ? "no_target" : execute(sender, action, target, targetText, role);
            if (target == null)
                reply(sender, "synaxis:role-no-target", "No online player or uuid %s", targetText);
            record(analytics, sender, input, target, result);
        });
    }

    private String execute(CommandSender sender, String action, UUID target, String targetText, @Nullable String role) {
        if (!permissions.hasDatabase()) {
            reply(sender, "synaxis:role-unavailable", "Roles cannot be reached right now");
            return "unavailable";
        }
        try {
            if ("list".equals(action)) {
                final Set<String> held = permissions.grantsOf(target).roles();
                reply(sender, "synaxis:role-list", "%s holds: %s", targetText, String.join(", ", held));
                return "ok";
            }
            final String roleId = role == null ? "" : role;
            // The console hands out any role, for good; a player none above what the database says
            // it holds now, itself included, and none for longer than its own grants run
            final RoleStore.Grants own = sender instanceof Player player ? permissions.grantsOf(player.getUuid()) : null;
            if (own != null && !permissions.mayHandOut(own, roleId)) {
                reply(sender, "synaxis:role-above-sender", "You cannot hand out %s: it grants nodes you do not hold", roleId);
                return "above_sender";
            }
            final RoleStore.Change change = "revoke".equals(action) ? permissions.revoke(target, roleId)
                    : own == null ? permissions.grant(target, roleId) : permissions.handOut(target, roleId, own.expiresAt());
            return switch (change) {
                case DONE -> {
                    if ("grant".equals(action)) reply(sender, "synaxis:role-granted", "Granted %s to %s", roleId, targetText);
                    else reply(sender, "synaxis:role-revoked", "Took %s back from %s", roleId, targetText);
                    yield "ok";
                }
                case UNKNOWN_ROLE -> {
                    reply(sender, "synaxis:role-unknown", "There is no role %s", roleId);
                    yield "unknown_role";
                }
                case NOT_GRANTED -> {
                    reply(sender, "synaxis:role-not-granted", "%s does not hold %s", targetText, roleId);
                    yield "not_granted";
                }
            };
        } catch (SQLException e) {
            failures.warn(e, "A role command failed: action={} target={}", action, target);
            reply(sender, "synaxis:role-unavailable", "Roles cannot be reached right now");
            return "unavailable";
        }
    }

    private @Nullable UUID resolve(String text) {
        if (UUID_TEXT.matcher(text).matches()) return UUID.fromString(text);
        final Player online = process.connection().getOnlinePlayerByUsername(text);
        return online == null ? null : online.getUuid();
    }
}
