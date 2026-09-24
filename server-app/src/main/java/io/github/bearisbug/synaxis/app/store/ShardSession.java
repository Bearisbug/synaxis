package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The session of one shard run in PostgreSQL, the epoch every lease of this shard points at
 * ({@code docs/DESIGN-PROD.md} §11.2, {@code API-PROD-004}, {@code ADR-PROD-004}).
 * <p>
 * The renewal runs on a platform thread of its own and on a connection of its own. Whether this
 * shard still holds its leases is only ever decided by the database's clock, through an update that
 * finds the session already expired; the local clock only decides when to freeze early.
 * <p>
 * The 10 s freeze and the 30 s isolation are decided by a watch thread of their own every
 * {@value #WATCH_EVERY_MS} ms, not by the renewal: a database that stops answering without closing
 * the connection holds the renewal inside its statement until the socket timeout of
 * {@link Database}, and the deadlines must not wait for it.
 */
public final class ShardSession implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardSession.class);
    static final long WATCH_EVERY_MS = 250;

    /** The states of §11.2. */
    public enum State {
        ACTIVE,
        /** Renewal has failed for more than 10 s: everybody is frozen. */
        DEGRADED,
        /** The session expired: this shard writes nothing and holds nobody. */
        ISOLATED,
        /** The shutdown save runs; renewal goes on. */
        CLOSING,
        /** The shutdown save is over and the session was made to expire. */
        RELEASED
    }

    /** Told about every state change, on the renewal thread or on the thread that caused it. */
    @FunctionalInterface
    public interface Listener {
        void stateChanged(State previous, State next);
    }

    /** Opens the renewal's own connection. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    /**
     * The clock of §11.2: renew every {@code renewEvery} to {@code now() + lease}; freeze once
     * renewal has failed for longer than {@code degradedAfter}, give up after {@code isolatedAfter}.
     */
    public record Timing(Duration renewEvery, Duration degradedAfter, Duration isolatedAfter, Duration lease) {
        public static final Timing DEFAULT = new Timing(Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    private final ConnectionSource source;
    private final String shardId;
    private final Timing timing;
    private final LongSupplier nanoClock;
    private final Listener listener;
    private final LogBudget failures = new LogBudget(LOGGER, "renewal failures");
    private final ScheduledExecutorService renewal = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("Syn-Shard-Session").daemon().factory());
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("Syn-Shard-Watch").daemon().factory());

    // Only touched on the renewal thread, and by open() before it starts
    private @Nullable Connection connection;
    // Guarded by this
    private long lastRenewed;

    private volatile long sessionId;
    private volatile State state = State.RELEASED;
    private volatile StoreMetrics metrics = StoreMetrics.NONE;

    public ShardSession(ConnectionSource source, String shardId, Timing timing, LongSupplier nanoClock, Listener listener) {
        this.source = source;
        this.shardId = shardId;
        this.timing = timing;
        this.nanoClock = nanoClock;
        this.listener = listener;
    }

    /**
     * {@code [*] → active}: inserts this run's session and makes the other sessions of the same
     * shard id expire in the same transaction, so the leases of the previous run of this shard can
     * be taken over at once. That presumes one process per shard id at a time (§11.2).
     *
     * @return the session id
     * @throws SQLException if the database cannot be reached
     */
    public long open() throws SQLException {
        final Connection opened = source.open();
        try {
            opened.setAutoCommit(false);
            final long id = insert(opened);
            try (PreparedStatement expire = opened.prepareStatement(
                    "UPDATE shard_session SET expires_at = now() WHERE shard_id = ? AND session_id <> ? AND expires_at > now()")) {
                expire.setString(1, shardId);
                expire.setLong(2, id);
                expire.executeUpdate();
            }
            opened.commit();
            opened.setAutoCommit(true);
            this.connection = opened;
            this.sessionId = id;
            synchronized (this) {
                this.lastRenewed = nanoClock.getAsLong();
            }
        } catch (SQLException e) {
            closeQuietly(opened);
            throw e;
        }
        transition(State.ACTIVE);
        LOGGER.info("Shard session opened: shard_id={} session_id={}", shardId, sessionId);
        final long every = timing.renewEvery().toMillis();
        var _ = renewal.scheduleWithFixedDelay(this::renewOrReopen, every, every, TimeUnit.MILLISECONDS);
        var _ = watch.scheduleAtFixedRate(this::checkDeadlines, WATCH_EVERY_MS, WATCH_EVERY_MS, TimeUnit.MILLISECONDS);
        return sessionId;
    }

    /**
     * Reports how long each renewal statement takes to the metrics endpoint from now on.
     *
     * @param metrics where it goes
     */
    public void useMetrics(StoreMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * The current session id, a new one after every {@code reopen}.
     *
     * @return the session id
     */
    public long sessionId() {
        return sessionId;
    }

    /**
     * The current state of §11.2.
     *
     * @return the state
     */
    public State state() {
        return state;
    }

    /**
     * {@code active | degraded → closing}: the shutdown save starts. Renewal goes on, otherwise the
     * session would expire in the middle of the save and fence its last writes.
     */
    public void beginClosing() {
        transition(State.CLOSING, State.ACTIVE, State.DEGRADED);
    }

    /**
     * {@code closing → released}: makes the session expire now, so whatever lease the save did not
     * give back can be taken over at once. Only made to expire, never deleted: character rows point
     * at it.
     */
    public void release() {
        try {
            renewal.submit(() -> {
                final Connection current = connection();
                try (PreparedStatement expire = current.prepareStatement(
                        "UPDATE shard_session SET expires_at = now() WHERE session_id = ?")) {
                    expire.setLong(1, sessionId);
                    expire.executeUpdate();
                }
                return null;
            }).get(Database.STATEMENT_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("Could not make the shard session expire, its leases are taken over once it runs out: "
                    + "session_id={}", sessionId, e);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        renewal.shutdownNow();
        watch.shutdownNow();
        transition(State.RELEASED);
    }

    /** One run of the renewal loop, also called by tests instead of waiting for the next one. */
    void renewOrReopen() {
        if (state == State.ISOLATED) reopen();
        else if (state != State.RELEASED) renew();
    }

    private void renew() {
        final long now = nanoClock.getAsLong();
        try (PreparedStatement update = connection().prepareStatement(
                "UPDATE shard_session SET expires_at = now() + make_interval(secs => ?) WHERE session_id = ? AND expires_at > now()")) {
            update.setLong(1, timing.lease().toSeconds());
            update.setLong(2, sessionId);
            final long statementStart = System.nanoTime();
            final int updated;
            try {
                updated = update.executeUpdate();
            } finally {
                metrics.statement(StoreMetrics.Statement.RENEW, System.nanoTime() - statementStart);
            }
            if (updated == 1) {
                final boolean recovered;
                synchronized (this) {
                    this.lastRenewed = now;
                    recovered = state == State.DEGRADED;
                    if (recovered) state = State.ACTIVE;
                }
                if (recovered) changed(State.DEGRADED, State.ACTIVE);
                return;
            }
            // The database says the session is over: another run of this shard id took its leases
            // over, or it ran out while renewal failed. A closing shard lets the leases run out.
            if (transition(State.ISOLATED, State.ACTIVE, State.DEGRADED))
                LOGGER.error("Shard session expired: shard_id={} session_id={}. Isolated this shard.", shardId, sessionId);
        } catch (SQLException e) {
            dropConnection();
            final long failingFor;
            synchronized (this) {
                failingFor = nanoClock.getAsLong() - lastRenewed;
            }
            failures.warn(e, "Shard session renewal failed: shard_id={} session_id={} failing_for_ms={}",
                    shardId, sessionId, TimeUnit.NANOSECONDS.toMillis(failingFor));
            checkDeadlines();
        }
    }

    /**
     * {@code active → degraded} past {@code degradedAfter} without a renewal, {@code active |
     * degraded → isolated} past {@code isolatedAfter}. Run by the watch thread, and by a failed
     * renewal; also called by tests instead of waiting for the next run.
     */
    void checkDeadlines() {
        final State previous;
        final State next;
        synchronized (this) {
            previous = state;
            if (previous != State.ACTIVE && previous != State.DEGRADED) return;
            final long failingFor = nanoClock.getAsLong() - lastRenewed;
            if (failingFor >= timing.isolatedAfter().toNanos()) next = State.ISOLATED;
            else if (failingFor > timing.degradedAfter().toNanos() && previous == State.ACTIVE) next = State.DEGRADED;
            else return;
            state = next;
        }
        if (next == State.ISOLATED)
            LOGGER.error("Shard session not renewed for {} s: shard_id={} session_id={}. Isolating this shard.",
                    timing.isolatedAfter().toSeconds(), shardId, sessionId);
        changed(previous, next);
    }

    /**
     * {@code isolated → active}: a new epoch, without making any other session expire. The
     * previous epoch of this run is made to expire first: isolated by the watch while its last
     * renewal was still under way, it may have been renewed after all, and its leases would stay
     * out of reach for another lease period.
     */
    private void reopen() {
        try {
            final Connection current = connection();
            try (PreparedStatement expire = current.prepareStatement(
                    "UPDATE shard_session SET expires_at = now() WHERE session_id = ? AND expires_at > now()")) {
                expire.setLong(1, sessionId);
                expire.executeUpdate();
            }
            final long id = insert(current);
            this.sessionId = id;
            synchronized (this) {
                this.lastRenewed = nanoClock.getAsLong();
            }
            LOGGER.info("Shard session reopened: shard_id={} session_id={}", shardId, id);
            transition(State.ACTIVE, State.ISOLATED);
        } catch (SQLException e) {
            dropConnection();
            failures.warn(e, "Shard session could not be reopened: shard_id={}", shardId);
        }
    }

    private long insert(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO shard_session (shard_id, started_at, expires_at) "
                        + "VALUES (?, now(), now() + make_interval(secs => ?)) RETURNING session_id")) {
            insert.setString(1, shardId);
            insert.setLong(2, timing.lease().toSeconds());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /**
     * Moves to {@code next} if the state is one of {@code from} (any state if none is given), the
     * check and the move under one lock.
     *
     * @return whether it moved
     */
    private boolean transition(State next, State... from) {
        final State previous;
        synchronized (this) {
            previous = this.state;
            if (previous == next || (from.length > 0 && !List.of(from).contains(previous))) return false;
            this.state = next;
        }
        changed(previous, next);
        return true;
    }

    private void changed(State previous, State next) {
        LOGGER.info("Shard session state: shard_id={} session_id={} {} -> {}", shardId, sessionId,
                previous.name().toLowerCase(Locale.ROOT), next.name().toLowerCase(Locale.ROOT));
        try {
            listener.stateChanged(previous, next);
        } catch (Throwable throwable) {
            LOGGER.error("Shard session listener failed on {} -> {}", previous, next, throwable);
        }
    }

    private Connection connection() throws SQLException {
        Connection current = this.connection;
        if (current == null || current.isClosed()) {
            current = source.open();
            this.connection = current;
        }
        return current;
    }

    private void dropConnection() {
        final Connection current = this.connection;
        this.connection = null;
        if (current != null) closeQuietly(current);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException _) {
            // Already broken, which is why it is being dropped
        }
    }

    @Override
    public void close() {
        watch.shutdownNow();
        renewal.shutdownNow();
        try {
            if (!renewal.awaitTermination(Database.STATEMENT_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS))
                LOGGER.warn("The shard session renewal did not stop in time");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        dropConnection();
    }
}
