package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.shard.ShardService;
import io.github.bearisbug.synaxis.app.shard.ShardTransferResult;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.Command;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentWord;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.Permissions;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static io.github.bearisbug.synaxis.app.command.GmCommands.record;
import static io.github.bearisbug.synaxis.app.command.GmCommands.reply;

/**
 * The GM command that sends a player to another shard of the map ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-011}, §10 node {@value #NODE}): {@code transfer region <player|uuid> <region_id>}
 * through {@link ShardService#transfer} and {@code transfer shard <player|uuid> <shard_id>} through
 * {@link ShardService#transferTo} ({@code docs/DESIGN-M3.md} {@code API-M3-010}). The player is looked
 * up among the players online on this shard; the address of the target comes from its heartbeat.
 * <p>
 * Only the console and a player holding {@value #NODE} can run it; for anybody else the engine
 * refuses it as a failed precondition and leaves it out of the completions. A process outside the
 * directory mode has no shard service and answers that it cannot transfer. The answer comes once
 * the transfer is known, on whatever thread completes it, and names the result in lower case.
 * Every run writes one {@code gm_command} event, its {@code result} being that name,
 * {@code no_target} or {@code unavailable}.
 */
public final class TransferCommand extends Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransferCommand.class);

    /** The node that lets a player run this command. */
    public static final String NODE = "gm.transfer";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ServerProcess process;
    private final @Nullable ShardService shards;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final LogBudget failures = new LogBudget(LOGGER, "transfer command failures");

    /**
     * @param process     the engine process, whose online players a name is looked up among
     * @param permissions who holds the node
     * @param shards      what sends a player to another shard, null outside the directory mode
     * @param analytics   the event ledger
     */
    public TransferCommand(ServerProcess process, Permissions permissions, @Nullable ShardService shards,
                           BiConsumer<String, Map<String, ?>> analytics) {
        super("transfer");
        this.process = process;
        this.shards = shards;
        this.analytics = analytics;
        setCondition((sender, _) -> !(sender instanceof Player player) || permissions.has(player, NODE));
        final ArgumentWord target = ArgumentType.Word("target");
        final ArgumentWord region = ArgumentType.Word("region_id");
        final ArgumentWord shard = ArgumentType.Word("shard_id");
        addSyntax((sender, context) -> run(sender, context.getInput(), context.get(target), context.get(region), false),
                ArgumentType.Literal("region"), target, region);
        addSyntax((sender, context) -> run(sender, context.getInput(), context.get(target), context.get(shard), true),
                ArgumentType.Literal("shard"), target, shard);
    }

    private void run(CommandSender sender, String input, String targetText, String destination, boolean toShard) {
        if (shards == null) {
            reply(sender, "synaxis:command-transfer-unavailable",
                    "This shard cannot send players to other shards: it is not in the directory mode");
            record(analytics, sender, input, null, "unavailable");
            return;
        }
        final Player player = online(targetText);
        if (player == null) {
            reply(sender, "synaxis:command-transfer-no-target", "%s is not a player online on this shard", targetText);
            record(analytics, sender, input, null, "no_target");
            return;
        }
        var _ = (toShard ? shards.transferTo(player, destination) : shards.transfer(player, destination))
                .whenComplete((result, error) -> finished(sender, input, player, destination, result, error));
    }

    private void finished(CommandSender sender, String input, Player player, String destination,
                          @Nullable ShardTransferResult result, @Nullable Throwable error) {
        if (result == null) {
            // What the transfer threw, wrapped once by the stages of the shard service
            final Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            failures.error(cause, "A transfer by the transfer command failed: player={} target={}", player.getUsername(),
                    destination);
            reply(sender, "synaxis:command-transfer-error", "The transfer of %s failed; the server log has the cause",
                    player.getUsername());
            record(analytics, sender, input, player.getUuid(), "unavailable");
            return;
        }
        final String name = result.name().toLowerCase(Locale.ROOT);
        reply(sender, "synaxis:command-transfer-result", "Transfer of %s to %s: %s", player.getUsername(), destination, name);
        record(analytics, sender, input, player.getUuid(), name);
    }

    private @Nullable Player online(String text) {
        if (UUID_TEXT.matcher(text).matches()) return process.connection().getOnlinePlayerByUuid(UUID.fromString(text));
        return process.connection().getOnlinePlayerByUsername(text);
    }
}
