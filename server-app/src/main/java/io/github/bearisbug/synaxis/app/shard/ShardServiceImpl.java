package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.app.transfer.TransferTickets;
import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The {@link ShardService} of a shard in the directory mode ({@code docs/DESIGN-M3.md}
 * {@code API-M3-010}): checks the target against the directory, chooses the channel on the
 * heartbeats, and hands the transfer to {@link TransferTickets}. The first check that fails decides:
 * how the target is written, on the directory alone; the heartbeat read, given up after 2 s; the
 * rows read; only then the tickets. The checks before the tickets leave the player alone and write
 * nothing. The choice is advice: the heartbeats are up to 5 s old, and the connection gate of the
 * target seats the player or not.
 */
public final class ShardServiceImpl implements ShardService {

    /** How long a heartbeat read may take, on top of its own statement timeout. */
    private static final long HEARTBEAT_TIMEOUT_MILLIS = 2_000;
    /** The least loaded first, then the smaller shard id; the capacity of a row is at least 1. */
    private static final Comparator<HeartbeatRow> LOAD = Comparator.<HeartbeatRow>comparingDouble(
            row -> (double) row.online() / row.capacity()).thenComparing(HeartbeatRow::shardId);

    private final ShardDirectory directory;
    private final HeartbeatReader heartbeats;
    private final TransferTickets tickets;
    private final String regionId;

    /**
     * @param directory  the shard directory of this shard
     * @param heartbeats where the channels are read
     * @param tickets    the source side of a transfer
     */
    public ShardServiceImpl(ShardDirectory directory, HeartbeatReader heartbeats, TransferTickets tickets) {
        this.directory = directory;
        this.heartbeats = heartbeats;
        this.tickets = tickets;
        this.regionId = directory.ownRegion().regionId();
    }

    @Override
    public String shardId() {
        return directory.shardId();
    }

    @Override
    public String regionId() {
        return regionId;
    }

    @Override
    public CompletableFuture<ShardTransferResult> transfer(Player player, String regionId) {
        if (regionId.equals(this.regionId)) return CompletableFuture.completedFuture(ShardTransferResult.SAME_TARGET);
        if (directory.region(regionId) == null) return CompletableFuture.completedFuture(ShardTransferResult.UNKNOWN_TARGET);
        return send(player, heartbeats.region(directory.mapId(), regionId), ShardServiceImpl::choose);
    }

    @Override
    public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
        if (shardId.equals(directory.shardId())) return CompletableFuture.completedFuture(ShardTransferResult.SAME_TARGET);
        return send(player, heartbeats.shard(directory.mapId(), shardId), ShardServiceImpl::check);
    }

    /** What the heartbeats say: the channel to send the player to, or why there is none. */
    private sealed interface Choice {
        record Channel(HeartbeatRow row) implements Choice {
        }

        record Refused(ShardTransferResult result) implements Choice {
        }
    }

    /** The least loaded live and accepting channel of a region. */
    private static Choice choose(List<HeartbeatRow> rows) {
        final List<HeartbeatRow> open = rows.stream().filter(row -> row.live() && row.accepting()).toList();
        if (open.isEmpty()) return new Choice.Refused(ShardTransferResult.OFFLINE);
        return open.stream().filter(row -> row.online() < row.capacity()).min(LOAD).<Choice>map(Choice.Channel::new)
                .orElse(new Choice.Refused(ShardTransferResult.FULL));
    }

    /** The one channel {@link #transferTo} names, if it takes the player. */
    private static Choice check(@Nullable HeartbeatRow row) {
        if (row == null) return new Choice.Refused(ShardTransferResult.UNKNOWN_TARGET);
        if (!row.live() || !row.accepting()) return new Choice.Refused(ShardTransferResult.OFFLINE);
        if (row.online() >= row.capacity()) return new Choice.Refused(ShardTransferResult.FULL);
        return new Choice.Channel(row);
    }

    private <T> CompletableFuture<ShardTransferResult> send(Player player, CompletableFuture<T> read, Function<T, Choice> choice) {
        return read.orTimeout(HEARTBEAT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .handle((rows, error) -> error == null ? choice.apply(rows)
                        : new Choice.Refused(ShardTransferResult.DIRECTORY_UNAVAILABLE))
                .thenCompose(chosen -> switch (chosen) {
                    case Choice.Refused(ShardTransferResult result) -> CompletableFuture.completedFuture(result);
                    case Choice.Channel(HeartbeatRow row) ->
                            tickets.transfer(player, row.shardId(), row.host(), row.port()).thenApply(ShardServiceImpl::result);
                });
    }

    private static ShardTransferResult result(TransferOutcome outcome) {
        return switch (outcome) {
            case SENT -> ShardTransferResult.SENT;
            case COOLDOWN -> ShardTransferResult.COOLDOWN;
            case NOT_ONLINE -> ShardTransferResult.NOT_ONLINE;
            case SAVE_FAILED -> ShardTransferResult.SAVE_FAILED;
        };
    }
}
