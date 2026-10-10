package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.app.transfer.TransferTickets;
import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The {@link ShardService} of a shard in the directory mode ({@code docs/DESIGN-M3.md}
 * {@code API-M3-010}): checks the target against the directory, chooses the channel on the
 * heartbeats, and hands the transfer to {@link TransferTickets}. The first check that fails decides:
 * how the target is written, on the directory alone; the heartbeat read, given up after 2 s; the
 * rows read; only then the tickets. The checks before the tickets leave the player alone and write
 * nothing. The choice is advice: the heartbeats are up to 5 s old, and the connection gate of the
 * target seats the player or not.
 * <p>
 * A gate reads the heartbeats of its target region at most once a second, however many players walk
 * into it ({@code REQ-M3-033}): a read in flight, or one done less than 1,000 ms ago, answers
 * {@link #transfer} again, its failure included. {@link #transferTo}, which a GM or the management
 * interface calls, always reads.
 */
public final class ShardServiceImpl implements ShardService {

    /** How long a heartbeat read may take, on top of its own statement timeout. */
    private static final long HEARTBEAT_TIMEOUT_MILLIS = 2_000;
    /** How long a done read of a region's heartbeats answers the transfers to that region. */
    private static final long REUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(1_000);
    /** The least loaded first, then the smaller shard id; the capacity of a row is at least 1. */
    private static final Comparator<HeartbeatRow> LOAD = Comparator.<HeartbeatRow>comparingDouble(
            row -> (double) row.online() / row.capacity()).thenComparing(HeartbeatRow::shardId);

    /** One read of the heartbeats of a region; {@code doneAt} is set before {@code rows} completes. */
    private static final class Read {
        final CompletableFuture<List<HeartbeatRow>> rows = new CompletableFuture<>();
        volatile long doneAt;
    }

    private final ShardDirectory directory;
    private final HeartbeatReader heartbeats;
    private final TransferTickets tickets;
    private final String regionId;
    private final LongSupplier clock;
    private final Map<String, Read> reads = new ConcurrentHashMap<>();

    /**
     * @param directory  the shard directory of this shard
     * @param heartbeats where the channels are read
     * @param tickets    the source side of a transfer
     */
    public ShardServiceImpl(ShardDirectory directory, HeartbeatReader heartbeats, TransferTickets tickets) {
        this(directory, heartbeats, tickets, System::nanoTime);
    }

    /** With the time a read is reused for measured on {@code clock}, in nanoseconds, for the tests. */
    ShardServiceImpl(ShardDirectory directory, HeartbeatReader heartbeats, TransferTickets tickets, LongSupplier clock) {
        this.directory = directory;
        this.heartbeats = heartbeats;
        this.tickets = tickets;
        this.regionId = directory.ownRegion().regionId();
        this.clock = clock;
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
        return send(player, region(regionId), ShardServiceImpl::choose);
    }

    @Override
    public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
        if (shardId.equals(directory.shardId())) return CompletableFuture.completedFuture(ShardTransferResult.SAME_TARGET);
        return send(player, heartbeats.shard(directory.mapId(), shardId)
                .orTimeout(HEARTBEAT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), ShardServiceImpl::check);
    }

    /**
     * The heartbeats of a region: the read in flight, or the last one if it was done less than
     * {@link #REUSE_NANOS} ago, whether it read the rows or failed; otherwise a new read, which takes the
     * place of the last one with one compare and set. Its 2 s timeout is set once, here.
     */
    private CompletableFuture<List<HeartbeatRow>> region(String target) {
        while (true) {
            final Read last = reads.get(target);
            if (last != null && (!last.rows.isDone() || clock.getAsLong() - last.doneAt < REUSE_NANOS)) return last.rows;
            final Read next = new Read();
            if (last == null ? reads.putIfAbsent(target, next) != null : !reads.replace(target, last, next)) continue;
            try {
                var _ = heartbeats.region(directory.mapId(), target).orTimeout(HEARTBEAT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        .whenComplete((rows, error) -> done(next, rows, error));
            } catch (RuntimeException e) {
                done(next, null, e);
            }
            return next.rows;
        }
    }

    private void done(Read read, @Nullable List<HeartbeatRow> rows, @Nullable Throwable error) {
        read.doneAt = clock.getAsLong();
        if (error != null) read.rows.completeExceptionally(error);
        else read.rows.complete(rows);
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

    /** {@code read} with its timeout set already. */
    private <T> CompletableFuture<ShardTransferResult> send(Player player, CompletableFuture<T> read, Function<T, Choice> choice) {
        return read.handle((rows, error) -> error == null ? choice.apply(rows)
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
