package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ServerProcess;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The heartbeat of a shard in the directory mode ({@code docs/DESIGN-M3.md} {@code API-M3-010},
 * internal task {@code shard.heartbeat}): writes this shard's row of {@code shard_heartbeat} once the
 * port is bound and every 5 s after, on a daemon thread of its own, and reads the rows for
 * {@link ShardService}.
 * <p>
 * The M3-E step 0 contract: the signatures {@code Main} is assembled against. The directory track
 * replaces the bodies.
 */
@SuppressWarnings("DoNotCallSuggester") // the bodies of the skeleton, replaced by the directory track
public final class ShardHeartbeat implements HeartbeatReader, AutoCloseable {

    /**
     * Builds the heartbeat; reads nothing and starts no thread.
     *
     * @param dataSource the player store database
     * @param directory  the shard directory, whose channel of this shard the row copies
     * @param buildId    the map release served, {@code synaxis.map.build}
     * @param process    the engine process, whose connection gate gives {@code online} and {@code accepting}
     */
    public ShardHeartbeat(DataSource dataSource, ShardDirectory directory, String buildId, ServerProcess process) {
        throw new UnsupportedOperationException("ShardHeartbeat is the M3-E step 0 skeleton");
    }

    /**
     * Writes the first row at once and one every 5 s after, on the daemon thread; step (7) of
     * {@code Main}, after the bind. Does nothing after {@link #close}: a stop begun between the bind
     * and step (7) runs {@code close} first, and the pool is closed by then.
     */
    public void start() {
        throw new UnsupportedOperationException("ShardHeartbeat is the M3-E step 0 skeleton");
    }

    @Override
    public CompletableFuture<List<HeartbeatRow>> region(String mapId, String regionId) {
        throw new UnsupportedOperationException("ShardHeartbeat is the M3-E step 0 skeleton");
    }

    @Override
    public CompletableFuture<@Nullable HeartbeatRow> shard(String mapId, String shardId) {
        throw new UnsupportedOperationException("ShardHeartbeat is the M3-E step 0 skeleton");
    }

    /**
     * Stops the writing thread, waiting at most for the statement timeout of a write in flight; writes
     * no last row. Before {@link #start} it only keeps {@code start} from starting the thread.
     */
    @Override
    public void close() {
        throw new UnsupportedOperationException("ShardHeartbeat is the M3-E step 0 skeleton");
    }
}
