package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TicketIssuer;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The gates of this shard's region ({@code docs/DESIGN-M3.md} {@code API-M3-010}, built into the host
 * as decided on 2026-09-28, §28.1): a player walking into the frame of a gate is sent to its target
 * region, once per walk in.
 * <p>
 * A walk in is a move whose new position, floored to its block, is in the frame of a gate, when the
 * last move of the player was outside it (outside every frame, or in another gate's). Moving inside
 * the frame sends nothing more, and neither does a walk in while a transfer of the player is under
 * way, or by a player that is not online here (security review B-01): one a transfer would turn
 * away (saving, in transit, still loading, or from the shutdown save on) reads no heartbeat and
 * writes nothing, so a client that ignores its {@code Transfer} cannot fill the ledger from here.
 * A choice of channel that failed ({@code FULL}, {@code OFFLINE},
 * {@code DIRECTORY_UNAVAILABLE}) is told to the player, who walks out and in again to retry. Every
 * refusal, those three and {@code COOLDOWN}, {@code NOT_ONLINE}, {@code SAVE_FAILED}, is written to
 * the ledger as {@code EVT:transfer_refused} ({@code docs/DESIGN-PROD.md} §20). What
 * should not happen (a result the start-up check of the directory rules out, a transfer that failed
 * exceptionally) is an ERROR, through a budget: how often a player walks into a frame is the
 * client's to pick.
 */
public final class ShardGates {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardGates.class);

    private final LogBudget errors = new LogBudget(LOGGER, "gate transfers that failed or led nowhere");

    private final ShardDirectory.Region region;
    private final ShardService service;
    private final String shardId;
    private final BiConsumer<String, Map<String, ?>> events;
    private final Predicate<Player> online;
    /** The gate each player was in at its last move; a player outside every frame has none. */
    private final Map<Player, ShardDirectory.Gate> inside = new ConcurrentHashMap<>();
    /** The players whose transfer through a gate has not completed yet. */
    private final Set<Player> underWay = ConcurrentHashMap.newKeySet();

    /**
     * @param directory the shard directory, whose region of this shard has the gates
     * @param service   what sends a player to the target region
     * @param events    where the events go, the event ledger of the host
     * @param online    whether a player is online here, as a transfer would take it: the player store's
     *                  {@code isOnline}
     */
    public ShardGates(ShardDirectory directory, ShardService service, BiConsumer<String, Map<String, ?>> events,
                      Predicate<Player> online) {
        this.region = directory.ownRegion();
        this.service = service;
        this.shardId = directory.shardId();
        this.events = events;
        this.online = online;
    }

    /**
     * Listens to {@code PlayerMoveEvent} on the map instance, and to {@code PlayerDisconnectEvent} to
     * forget a player that left.
     *
     * @param instance the map instance
     */
    public void attach(Instance instance) {
        instance.eventNode().addListener(PlayerMoveEvent.class, this::moved)
                .addListener(PlayerDisconnectEvent.class, event -> inside.remove(event.getPlayer()));
    }

    private void moved(PlayerMoveEvent event) {
        final Player player = event.getPlayer();
        final Pos to = event.getNewPosition();
        final ShardDirectory.Gate gate = region.gateAt(to.blockX(), to.blockY(), to.blockZ());
        final ShardDirectory.Gate before = gate == null ? inside.remove(player) : inside.put(player, gate);
        // The frame is recorded whatever follows, and online is asked before underWay.add, which comes last:
        // only finished() takes the player out of underWay again
        if (gate == null || gate.equals(before) || !online.test(player) || !underWay.add(player)) return;
        var _ = service.transfer(player, gate.targetRegion())
                .whenComplete((result, error) -> finished(player, gate, result, error));
    }

    private void finished(Player player, ShardDirectory.Gate gate, @Nullable ShardTransferResult result,
                          @Nullable Throwable error) {
        underWay.remove(player);
        if (result == null) {
            // What TransferTickets threw, wrapped once by the stages of ShardServiceImpl
            final Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            errors.error(cause, "A transfer through a gate failed: player={} gate={}", player.getUsername(), gate.gateId());
            return;
        }
        switch (result) {
            case SENT -> {
            }
            // SAVE_FAILED: the source side of the transfer has told the player already
            case COOLDOWN, NOT_ONLINE, SAVE_FAILED -> refused(player, gate, result);
            case FULL, OFFLINE, DIRECTORY_UNAVAILABLE -> {
                player.sendMessage(Component.translatable(TicketIssuer.TRANSFER_FAILED));
                refused(player, gate, result);
            }
            // The start-up check of the shard directory rules both out
            case UNKNOWN_TARGET, SAME_TARGET -> errors.error(new IllegalStateException("the shard service answers " + result),
                    "A gate leads nowhere a transfer can go: player={} gate={} target_region={}", player.getUsername(),
                    gate.gateId(), gate.targetRegion());
        }
    }

    /** {@code EVT:transfer_refused}: the transfer through the gate sent no ticket; the player may be gone. */
    private void refused(Player player, ShardDirectory.Gate gate, ShardTransferResult result) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("player_uuid", player.getUuid());
        attributes.put("source_shard", shardId);
        attributes.put("target_region", gate.targetRegion());
        attributes.put("result", result.name().toLowerCase(Locale.ROOT));
        events.accept("transfer_refused", attributes);
    }
}
