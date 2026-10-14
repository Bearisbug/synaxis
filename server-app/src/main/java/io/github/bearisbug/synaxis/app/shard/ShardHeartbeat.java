package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The heartbeat of a shard in the directory mode ({@code docs/DESIGN-M3.md} {@code API-M3-010},
 * internal task {@code shard.heartbeat}): writes this shard's row of {@code shard_heartbeat} once the
 * port is bound and every 5 s after, on a daemon thread of its own, and reads the rows for
 * {@link ShardService}.
 * <p>
 * Every statement runs on the pool of the player store, whose connections carry the statement timeout
 * of 2 s ({@code docs/DESIGN-PROD.md} {@code API-PROD-004}). A write that fails is only a WARN, one
 * for every three in a row ({@code RB-M3-004}): the next one goes on as usual, and the other shards
 * take this one for gone once its row is 15 s old.
 */
public final class ShardHeartbeat implements HeartbeatReader, AutoCloseable {
    /** The name of the writing thread. */
    static final String THREAD = "Syn-Shard-Heartbeat";
    /** The name of the virtual threads the reads run on. */
    private static final String READ_THREAD = "Syn-Shard-Heartbeat-Read";
    private static final Duration INTERVAL = Duration.ofSeconds(5);
    /** How long {@link #close} waits for a write in flight: the statement timeout of the pool. */
    private static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(2);
    /** A WARN for every this many failed writes in a row. */
    private static final int FAILURES_PER_WARN = 3;
    /** How old a row may be and still be live, by the database clock. */
    private static final int LIVE_SECONDS = 15;

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardHeartbeat.class);

    private static final String WRITE = "INSERT INTO shard_heartbeat (shard_id, map_id, region_id, channel, host, port, "
            + "build_id, online, capacity, accepting, started_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
            + "ON CONFLICT (shard_id) DO UPDATE SET map_id = EXCLUDED.map_id, region_id = EXCLUDED.region_id, "
            + "channel = EXCLUDED.channel, host = EXCLUDED.host, port = EXCLUDED.port, build_id = EXCLUDED.build_id, "
            + "online = EXCLUDED.online, capacity = EXCLUDED.capacity, accepting = EXCLUDED.accepting, "
            + "started_at = EXCLUDED.started_at, updated_at = EXCLUDED.updated_at";
    private static final String READ = "SELECT shard_id, region_id, channel, host, port, online, capacity, accepting, "
            + "updated_at > now() - interval '" + LIVE_SECONDS + " seconds' AS live FROM shard_heartbeat ";
    private static final String READ_REGION = READ + "WHERE map_id = ? AND region_id = ?";
    private static final String READ_SHARD = READ + "WHERE map_id = ? AND shard_id = ?";

    private final DataSource dataSource;
    private final String mapId;
    private final ShardDirectory.Channel channel;
    private final String buildId;
    private final ConnectionGate gate;
    private final OffsetDateTime startedAt;
    // The pool starts its thread with the first task, in start()
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name(THREAD).daemon().factory());

    // Guarded by this
    private boolean started;
    private boolean closed;
    // Only touched by the writing thread, and by the tests calling beat() instead of it
    private int failuresInARow;
    private boolean written;

    /**
     * Builds the heartbeat; reads nothing and starts no thread.
     *
     * @param dataSource the player store database
     * @param directory  the shard directory, whose channel of this shard the row copies
     * @param buildId    the map release served, {@code synaxis.map.build}
     * @param process    the engine process, whose connection gate gives {@code online} and {@code accepting}
     */
    public ShardHeartbeat(DataSource dataSource, ShardDirectory directory, String buildId, ServerProcess process) {
        this.dataSource = dataSource;
        this.mapId = directory.mapId();
        this.channel = directory.ownChannel();
        this.buildId = buildId;
        this.gate = process.connection().gate();
        // One value for the whole process, however often the row is written
        this.startedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
                ZoneOffset.UTC);
    }

    /**
     * Writes the first row at once and one every 5 s after, on the daemon thread; step (7) of
     * {@code Main}, after the bind. Does nothing after {@link #close}: a stop begun between the bind
     * and step (7) runs {@code close} first, and the pool is closed by then.
     */
    public void start() {
        final long every = INTERVAL.toMillis();
        // Under the lock close takes before it shuts the pool down: a close on another thread either comes
        // after and cancels the task, or comes before and is seen here, so no task reaches a pool shut down
        synchronized (this) {
            if (closed || started) return;
            started = true;
            var _ = writer.scheduleAtFixedRate(this::beat, 0, every, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * One write of the row, what the writing thread runs every 5 s. Never throws: the scheduler would
     * stop the task for good.
     */
    void beat() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement write = connection.prepareStatement(WRITE)) {
            write.setString(1, channel.shardId());
            write.setString(2, mapId);
            write.setString(3, channel.regionId());
            write.setInt(4, channel.channel());
            write.setString(5, channel.host());
            write.setInt(6, channel.port());
            write.setString(7, buildId);
            // The seats L3 counts, the same number it turns a login away on
            write.setInt(8, gate.seats());
            write.setInt(9, channel.capacity());
            write.setBoolean(10, !gate.isClosed());
            write.setObject(11, startedAt);
            write.executeUpdate();
            // The first row, and the first after a run of failures that was a WARN: the one line that names the
            // address the other shards send players to, which need not be the bound one
            if (!written || failuresInARow >= FAILURES_PER_WARN) {
                LOGGER.info("Shard heartbeat written: shard_id={} region_id={} address={}:{} capacity={}; other shards "
                        + "send players to this address", channel.shardId(), channel.regionId(), channel.host(),
                        channel.port(), channel.capacity());
            }
            written = true;
            failuresInARow = 0;
        } catch (SQLException | RuntimeException e) {
            failuresInARow++;
            if (failuresInARow % FAILURES_PER_WARN == 0) {
                LOGGER.warn("Shard heartbeat not written {} times in a row: shard_id={}; the other shards take this one "
                        + "for gone once its row is " + LIVE_SECONDS + " s old", failuresInARow, channel.shardId(), e);
            } else {
                LOGGER.debug("Shard heartbeat not written: shard_id={}", channel.shardId(), e);
            }
        }
    }

    @Override
    public CompletableFuture<List<HeartbeatRow>> region(String mapId, String regionId) {
        return read(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(READ_REGION)) {
                select.setString(1, mapId);
                select.setString(2, regionId);
                try (ResultSet result = select.executeQuery()) {
                    final List<HeartbeatRow> rows = new ArrayList<>();
                    while (result.next()) rows.add(row(result));
                    return rows;
                }
            }
        });
    }

    @Override
    public CompletableFuture<@Nullable HeartbeatRow> shard(String mapId, String shardId) {
        return read(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(READ_SHARD)) {
                select.setString(1, mapId);
                select.setString(2, shardId);
                try (ResultSet result = select.executeQuery()) {
                    return result.next() ? row(result) : null;
                }
            }
        });
    }

    /** A read of the table, which may fail. */
    @FunctionalInterface
    private interface Read<T> {
        T run() throws SQLException;
    }

    /** Runs a read on a virtual thread of its own; the future fails with what the read threw. */
    private static <T> CompletableFuture<T> read(Read<T> read) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().name(READ_THREAD).start(() -> {
            try {
                future.complete(read.run());
            } catch (SQLException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static HeartbeatRow row(ResultSet result) throws SQLException {
        return new HeartbeatRow(result.getString("shard_id"), result.getString("region_id"), result.getInt("channel"),
                result.getString("host"), result.getInt("port"), result.getInt("online"), result.getInt("capacity"),
                result.getBoolean("accepting"), result.getBoolean("live"));
    }

    /**
     * Stops the writing thread, waiting at most 2 s, the statement timeout, for a write in flight; writes
     * no last row. A write can take longer, up to 2 s for a connection and 4 s on the socket: one still
     * running then fails once the pool closes after this. Before {@link #start} it only keeps
     * {@code start} from starting the thread.
     */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        writer.shutdownNow();
        try {
            if (!writer.awaitTermination(STATEMENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                LOGGER.warn("The shard heartbeat did not stop within {} ms: shard_id={}", STATEMENT_TIMEOUT.toMillis(),
                        channel.shardId());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
