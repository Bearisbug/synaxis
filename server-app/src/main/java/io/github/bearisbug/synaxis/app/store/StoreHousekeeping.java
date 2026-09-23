package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The cleanup jobs of the store ({@code docs/DESIGN-PROD.md} §17): {@code transfer_nonce.gc}
 * every 10 minutes and {@code shard_session.cleanup} every hour. Any shard may run them; a
 * transaction scoped advisory lock lets one of them run a job at a time and the others skip it.
 */
public final class StoreHousekeeping implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreHousekeeping.class);

    /** The advisory lock keys of the two jobs, arbitrary but fixed. */
    static final long NONCE_GC_LOCK = 0x5359_4E58_0001L;
    static final long SESSION_CLEANUP_LOCK = 0x5359_4E58_0002L;

    // Past its expiry a ticket is refused by check ③ anyway, so deleting its nonce lets nothing replay
    private static final String NONCE_GC = "DELETE FROM transfer_nonce WHERE expires_at < now()";
    // Every start and every reopen adds a session; a day old and pointed at by nobody, it can go
    private static final String SESSION_CLEANUP = """
            DELETE FROM shard_session s WHERE s.expires_at < now() - interval '1 day'
               AND NOT EXISTS (SELECT 1 FROM character c WHERE c.lease_session_id = s.session_id)""";

    private final DataSource dataSource;
    private final LogBudget failures = new LogBudget(LOGGER, "cleanup failures");
    private final ScheduledExecutorService schedule = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("Syn-Store-Cleanup").factory());

    public StoreHousekeeping(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Schedules both jobs.
     */
    public void start() {
        var _ = schedule.scheduleWithFixedDelay(this::collectNonces, 10, 10, TimeUnit.MINUTES);
        var _ = schedule.scheduleWithFixedDelay(this::cleanSessions, 1, 1, TimeUnit.HOURS);
    }

    /**
     * {@code transfer_nonce.gc}: deletes the nonces of expired tickets.
     *
     * @return the rows deleted, -1 if another shard holds the job or it failed
     */
    int collectNonces() {
        return run(NONCE_GC_LOCK, NONCE_GC, 10);
    }

    /**
     * {@code shard_session.cleanup}: deletes sessions expired for a day that no lease points at.
     *
     * @return the rows deleted, -1 if another shard holds the job or it failed
     */
    int cleanSessions() {
        return run(SESSION_CLEANUP_LOCK, SESSION_CLEANUP, 30);
    }

    private int run(long lock, String sql, int timeoutSeconds) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement take = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
                    take.setLong(1, lock);
                    try (ResultSet row = take.executeQuery()) {
                        if (!row.next() || !row.getBoolean(1)) {
                            connection.rollback();
                            return -1;
                        }
                    }
                }
                final int deleted;
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = '" + timeoutSeconds + "s'");
                    deleted = statement.executeUpdate(sql);
                }
                connection.commit();
                return deleted;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            failures.warn(e, "A store cleanup job failed, the next run makes up for it");
            return -1;
        }
    }

    @Override
    public void close() {
        schedule.shutdownNow();
    }
}
