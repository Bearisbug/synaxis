package io.github.bearisbug.synaxis.app.shard;

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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gates of this shard's region ({@code docs/DESIGN-M3.md} {@code API-M3-010}, built into the host
 * as decided on 2026-09-28, §28.1): a player walking into the frame of a gate is sent to its target
 * region, once per walk in.
 * <p>
 * A walk in is a move whose new position, floored to its block, is in the frame of a gate, when the
 * last move of the player was outside it (outside every frame, or in another gate's). Moving inside
 * the frame sends nothing more, and neither does a walk in while a transfer of the player is under
 * way. A choice of channel that failed ({@code FULL}, {@code OFFLINE},
 * {@code DIRECTORY_UNAVAILABLE}) is told to the player, who walks out and in again to retry. What
 * should not happen (a result the start-up check of the directory rules out, a transfer that failed
 * exceptionally) is an ERROR, through a budget: how often a player walks into a frame is the
 * client's to pick.
 */
public final class ShardGates {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardGates.class);
    /** What the player is told when no channel of the target region could be chosen. */
    private static final String TRANSFER_FAILED = "synaxis:transfer-failed";

    private final LogBudget errors = new LogBudget(LOGGER, "gate transfers that failed or led nowhere");

    private final List<ShardDirectory.Gate> gates;
    private final ShardService service;
    /** The gate each player was in at its last move; a player outside every frame has none. */
    private final Map<Player, ShardDirectory.Gate> inside = new ConcurrentHashMap<>();
    /** The players whose transfer through a gate has not completed yet. */
    private final Set<Player> underWay = ConcurrentHashMap.newKeySet();

    /**
     * @param directory the shard directory, whose region of this shard has the gates
     * @param service   what sends a player to the target region
     */
    public ShardGates(ShardDirectory directory, ShardService service) {
        this.gates = directory.ownRegion().gates();
        this.service = service;
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
        final ShardDirectory.Gate gate = gateAt(to.blockX(), to.blockY(), to.blockZ());
        final ShardDirectory.Gate before = gate == null ? inside.remove(player) : inside.put(player, gate);
        if (gate == null || gate.equals(before) || !underWay.add(player)) return;
        var _ = service.transfer(player, gate.targetRegion())
                .whenComplete((result, error) -> finished(player, gate, result, error));
    }

    private @Nullable ShardDirectory.Gate gateAt(int x, int y, int z) {
        for (ShardDirectory.Gate gate : gates) {
            if (gate.frame().contains(x, y, z)) return gate;
        }
        return null;
    }

    private void finished(Player player, ShardDirectory.Gate gate, @Nullable ShardTransferResult result,
                          @Nullable Throwable error) {
        underWay.remove(player);
        if (result == null) {
            errors.error(error, "A transfer through a gate failed: player={} gate={}", player.getUsername(), gate.gateId());
            return;
        }
        switch (result) {
            // SAVE_FAILED: the source side of the transfer has told the player already
            case SENT, COOLDOWN, NOT_ONLINE, SAVE_FAILED -> {
            }
            case FULL, OFFLINE, DIRECTORY_UNAVAILABLE -> player.sendMessage(Component.translatable(TRANSFER_FAILED));
            // The start-up check of the shard directory rules both out
            case UNKNOWN_TARGET, SAME_TARGET -> errors.error(new IllegalStateException("the shard service answers " + result),
                    "A gate leads nowhere a transfer can go: player={} gate={} target_region={}", player.getUsername(),
                    gate.gateId(), gate.targetRegion());
        }
    }
}
