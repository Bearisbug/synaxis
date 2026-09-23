package io.github.bearisbug.synaxis.app.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shard session of {@code docs/DESIGN-PROD.md} §11.2 against a real PostgreSQL, one transition
 * of the state machine at a time. The renewal loop is driven by hand ({@code renewOrReopen}) on a
 * clock the test moves, so no test waits for a real 10 or 30 seconds.
 */
class ShardSessionDbTest {

    /** Renewal is driven by hand, the scheduled loop never gets a turn during a test. */
    private static final ShardSession.Timing TIMING = new ShardSession.Timing(Duration.ofHours(1),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(30));

    private TestDatabase database;
    private Database pool;
    private final List<ShardSession> sessions = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private final AtomicBoolean unreachable = new AtomicBoolean();
    private final List<String> transitions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        for (ShardSession session : sessions) session.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private ShardSession session(String shardId) {
        final ShardSession session = new ShardSession(() -> {
            if (unreachable.get()) throw new SQLException("the test cut the database off");
            return pool.dedicated();
        }, shardId, TIMING, clock::get, (previous, next) -> transitions.add(previous + "->" + next));
        sessions.add(session);
        return session;
    }

    @Test
    void startingExpiresThePreviousRunOfTheSameShardOnly() throws SQLException {
        final long previousRun = session("r1-c1").open();
        final long otherShard = session("r2-c1").open();

        final long thisRun = session("r1-c1").open();

        assertNotEquals(previousRun, thisRun);
        assertFalse(alive(previousRun), "the leases of the previous run can be taken over at once");
        assertTrue(alive(thisRun));
        assertTrue(alive(otherShard), "another shard id is left alone");
    }

    @Test
    void renewalPushesTheExpiryAndAnExpiredSessionIsolatesThenReopens() throws SQLException {
        final ShardSession session = session("r1-c1");
        final long first = session.open();
        database.execute("UPDATE shard_session SET expires_at = now() + interval '5 seconds' WHERE session_id = " + first);

        session.renewOrReopen();
        assertTrue(secondsLeft(first) > 20, "renewed to now() + 30 s");
        assertEquals(ShardSession.State.ACTIVE, session.state());

        database.execute("UPDATE shard_session SET expires_at = now() - interval '1 second' WHERE session_id = " + first);
        session.renewOrReopen();
        assertEquals(ShardSession.State.ISOLATED, session.state(), "the database said the session is over");

        final long twin = session("r1-c1").open();
        database.execute("UPDATE shard_session SET expires_at = now() + interval '30 seconds' WHERE session_id = " + twin);
        session.renewOrReopen();
        assertEquals(ShardSession.State.ACTIVE, session.state());
        assertTrue(session.sessionId() > twin, "a new epoch");
        assertTrue(alive(twin), "reopening does not expire the other sessions of the shard id");
        assertTrue(transitions.containsAll(List.of("RELEASED->ACTIVE", "ACTIVE->ISOLATED", "ISOLATED->ACTIVE")),
                transitions.toString());
    }

    @Test
    void aRenewalFailingForTenSecondsDegradesAndForThirtyIsolates() throws SQLException {
        final ShardSession session = session("r1-c1");
        session.open();
        cutOff();

        clock.set(TimeUnit.SECONDS.toNanos(9));
        session.renewOrReopen();
        assertEquals(ShardSession.State.ACTIVE, session.state(), "9 s of failures is not yet 10");
        clock.set(TimeUnit.SECONDS.toNanos(11));
        session.renewOrReopen();
        assertEquals(ShardSession.State.DEGRADED, session.state());

        unreachable.set(false);
        session.renewOrReopen();
        assertEquals(ShardSession.State.ACTIVE, session.state(), "one renewal gets it back");

        cutOff();
        clock.set(TimeUnit.SECONDS.toNanos(22));
        session.renewOrReopen();
        assertEquals(ShardSession.State.DEGRADED, session.state());
        clock.set(TimeUnit.SECONDS.toNanos(42));
        session.renewOrReopen();
        assertEquals(ShardSession.State.ISOLATED, session.state(), "30 s without a renewal");
    }

    @Test
    void closingKeepsRenewingAndTheReleaseExpiresTheSession() throws SQLException {
        final ShardSession session = session("r1-c1");
        final long id = session.open();

        session.beginClosing();
        assertEquals(ShardSession.State.CLOSING, session.state());
        database.execute("UPDATE shard_session SET expires_at = now() + interval '5 seconds' WHERE session_id = " + id);
        session.renewOrReopen();
        assertTrue(secondsLeft(id) > 20, "a closing shard keeps renewing, or its last saves would be fenced");
        database.execute("UPDATE shard_session SET expires_at = now() - interval '1 second' WHERE session_id = " + id);
        session.renewOrReopen();
        assertEquals(ShardSession.State.CLOSING, session.state(), "an expired closing session does not isolate");

        database.execute("UPDATE shard_session SET expires_at = now() + interval '30 seconds' WHERE session_id = " + id);
        session.release();

        assertEquals(ShardSession.State.RELEASED, session.state());
        assertFalse(alive(id), "released means expired now, the row stays");
    }

    /** Kills the renewal's connection on the server and keeps it from reconnecting. */
    private void cutOff() {
        unreachable.set(true);
        database.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND pid <> pg_backend_pid()");
    }

    private boolean alive(long sessionId) throws SQLException {
        return secondsLeft(sessionId) > 0;
    }

    private double secondsLeft(long sessionId) throws SQLException {
        try (Connection connection = database.connect(); PreparedStatement select = connection.prepareStatement(
                "SELECT extract(epoch FROM expires_at - now()) FROM shard_session WHERE session_id = ?")) {
            select.setLong(1, sessionId);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next(), "session " + sessionId + " has a row");
                return row.getDouble(1);
            }
        }
    }
}
