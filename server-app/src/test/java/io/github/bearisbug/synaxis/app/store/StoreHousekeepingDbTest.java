package io.github.bearisbug.synaxis.app.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.count;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cleanup jobs of {@code docs/DESIGN-PROD.md} §17: {@code transfer_nonce.gc} and
 * {@code shard_session.cleanup}, each run by one shard at a time under an advisory lock.
 */
class StoreHousekeepingDbTest {

    private TestDatabase database;
    private Database pool;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    @Test
    void onlyExpiredNoncesAreCollected() throws SQLException {
        database.execute("INSERT INTO transfer_nonce VALUES (decode(repeat('01', 16), 'hex'), now() - interval '1 second'),"
                + " (decode(repeat('02', 16), 'hex'), now() - interval '1 hour'),"
                + " (decode(repeat('03', 16), 'hex'), now() + interval '30 seconds')");

        assertEquals(2, new StoreHousekeeping(pool.pool()).collectNonces());

        assertEquals(1, count(database, "SELECT count(*) FROM transfer_nonce"), "the live ticket's nonce stays");
    }

    @Test
    void onlySessionsExpiredForADayThatNoLeasePointsAtAreDeleted() throws SQLException {
        final UUID account = UUID.randomUUID();
        database.execute("INSERT INTO shard_session (shard_id, started_at, expires_at) VALUES "
                + "('old', now() - interval '3 days', now() - interval '2 days'),"
                + "('held', now() - interval '3 days', now() - interval '2 days'),"
                + "('recent', now() - interval '1 hour', now() - interval '1 minute'),"
                + "('live', now(), now() + interval '30 seconds');"
                + "INSERT INTO account (account_uuid, last_name) VALUES ('" + account + "', 'Orphan');"
                + "INSERT INTO character (account_uuid, slot, lease_session_id) SELECT '" + account + "', 0, session_id "
                + "FROM shard_session WHERE shard_id = 'held'");

        assertEquals(1, new StoreHousekeeping(pool.pool()).cleanSessions());

        assertEquals(0, count(database, "SELECT count(*) FROM shard_session WHERE shard_id = 'old'"));
        assertEquals(3, count(database, "SELECT count(*) FROM shard_session"),
                "a session a lease points at, one expired less than a day ago and a live one stay");
    }

    @Test
    void aJobAnotherShardIsRunningIsSkipped() throws SQLException {
        database.execute("INSERT INTO transfer_nonce VALUES (decode(repeat('01', 16), 'hex'), now() - interval '1 second')");
        try (Connection other = database.connect(); Statement lock = other.createStatement()) {
            lock.execute("SELECT pg_advisory_lock(" + StoreHousekeeping.NONCE_GC_LOCK + ")");

            assertEquals(-1, new StoreHousekeeping(pool.pool()).collectNonces());
            assertEquals(1, count(database, "SELECT count(*) FROM transfer_nonce"));

            lock.execute("SELECT pg_advisory_unlock(" + StoreHousekeeping.NONCE_GC_LOCK + ")");
        }
        assertEquals(1, new StoreHousekeeping(pool.pool()).collectNonces(), "the next run does it");
    }

    /**
     * A cleanup statement may run as long as its own timeout says, 10 s for the nonces (§17), not only the 4 s the
     * driver waits on the socket of every statement of the pool: a delete held up 6 s behind a lock still finishes.
     */
    @Test
    void aCleanupMayRunForItsOwnTimeoutPastTheSocketTimeoutOfThePool() throws Exception {
        database.execute("INSERT INTO transfer_nonce VALUES (decode(repeat('01', 16), 'hex'), now() - interval '1 second')");
        try (Connection other = database.connect(); Statement lock = other.createStatement()) {
            other.setAutoCommit(false);
            lock.execute("LOCK TABLE transfer_nonce IN ACCESS EXCLUSIVE MODE");
            final Thread release = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(6_000);
                    other.commit();
                } catch (InterruptedException | SQLException e) {
                    throw new IllegalStateException(e);
                }
            });

            assertEquals(1, new StoreHousekeeping(pool.pool()).collectNonces(), "the delete waited for the lock");
            release.join();
        }
    }
}
