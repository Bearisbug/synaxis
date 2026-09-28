package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The writes and reads of the world state flags against a real PostgreSQL ({@code docs/DESIGN-M3.md}
 * {@code API-M3-013}, §19 「两个频道同时改同一个标志」, §10 the rows of a shard): a write only on the
 * version the caller saw, the loser handed the winner's row, and nothing outside the map and region of
 * the shard read or written. No map is mounted: a write only schedules an application once there is an
 * instance.
 */
@Timeout(60)
class WorldFlagStoreDbTest {

    private static final String MAP = "fixture";
    private static final String GATE = "quest:gate";

    private TestDatabase database;
    private Database pool;
    private final List<WorldFlagsImpl> channels = new ArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        for (WorldFlagsImpl channel : channels) channel.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private WorldFlagsImpl channel(DataSource dataSource, String regionId) {
        final WorldFlagsImpl channel = new WorldFlagsImpl(dataSource, RedisLink.disabled(regionId + "-c" + channels.size()),
                MAP, regionId, MapFixture.BOUNDS);
        channels.add(channel);
        return channel;
    }

    private static JsonElement json(String value) {
        return new JsonPrimitive(value);
    }

    private long version(String regionId, String key) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT version FROM world_flag WHERE map_id = '" + MAP
                     + "' AND region_id = '" + regionId + "' AND flag_key = '" + key + "'")) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    @Test
    void aNewKeyIsWrittenAsVersionOneAndThenOnlyOnTheVersionInTheDatabase() throws SQLException {
        final WorldFlagsImpl flags = channel(pool.pool(), "r0");

        assertEquals(new FlagWrite.Written(1), flags.set(GATE, json("open"), 0).join());
        assertEquals(new FlagWrite.Conflict(new FlagValue(json("open"), 1)), flags.set(GATE, json("closed"), 0).join(),
                "a second new key loses to the row there");
        assertEquals(new FlagWrite.Written(2), flags.set(GATE, json("closed"), 1).join());
        assertEquals(new FlagWrite.Conflict(new FlagValue(json("closed"), 2)), flags.set(GATE, json("open"), 1).join(),
                "a stale version loses, handed the row as it is");
        assertEquals(new FlagWrite.Conflict(null), flags.set("quest:missing", json("x"), 3).join(),
                "a version for a key that is not there");
        assertEquals(2, version("r0", GATE));
        assertEquals(0, version("r0", "quest:missing"));
    }

    /** §19 「两个频道同时改同一个标志」, over several rounds so that the two writes do overlap. */
    @Test
    void twoChannelsWritingTheSameVersionAtOnceHaveExactlyOneWinner() throws SQLException {
        final WorldFlagsImpl first = channel(pool.pool(), "r0");
        final WorldFlagsImpl second = channel(pool.pool(), "r0");
        for (int round = 0; round < 20; round++) {
            final String key = "quest:gate" + round;
            database.execute("INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES ('" + MAP
                    + "', 'r0', '" + key + "', '\"closed\"', 5)");

            final CompletableFuture<FlagWrite> one = first.set(key, json("open-1"), 5);
            final CompletableFuture<FlagWrite> two = second.set(key, json("open-2"), 5);
            assertOneWinner(one.join(), two.join(), 6);
            assertEquals(6, version("r0", key));

            final String fresh = "quest:new" + round;
            final CompletableFuture<FlagWrite> three = first.set(fresh, json("open-1"), 0);
            final CompletableFuture<FlagWrite> four = second.set(fresh, json("open-2"), 0);
            assertOneWinner(three.join(), four.join(), 1);
            assertEquals(1, version("r0", fresh));
        }
    }

    private static void assertOneWinner(FlagWrite one, FlagWrite two, long version) {
        final boolean firstWon = one instanceof FlagWrite.Written;
        final FlagWrite winner = firstWon ? one : two;
        final FlagWrite loser = firstWon ? two : one;
        assertEquals(new FlagWrite.Written(version), winner, one + " / " + two);
        final FlagWrite.Conflict conflict = assertInstanceOf(FlagWrite.Conflict.class, loser, one + " / " + two);
        final FlagValue current = conflict.current();
        assertNotNull(current);
        assertEquals(version, current.version());
        assertEquals(json(firstWon ? "open-1" : "open-2"), current.value(), "the loser gets the winner's value");
    }

    /** §10: every statement carries the map and the region of the shard. */
    @Test
    void aShardReadsAndWritesTheRowsOfItsMapAndRegionOnly() throws SQLException {
        database.execute("INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES "
                + "('" + MAP + "', 'r0', 'quest:gate', '\"open\"', 2), "
                + "('" + MAP + "', 'r1', 'quest:gate', '\"closed\"', 7), "
                + "('other', 'r0', 'quest:gate', '\"closed\"', 9)");
        final WorldFlagStore store = new WorldFlagStore(pool.pool(), MAP, "r0");

        assertEquals(List.of(new WorldFlagStore.Row(GATE, json("open"), 2)), store.readAll());
        assertEquals(new WorldFlagStore.Row(GATE, json("open"), 2), store.read(GATE));
        assertNull(new WorldFlagStore(pool.pool(), MAP, "r2").read(GATE));

        assertEquals(new FlagWrite.Written(3), channel(pool.pool(), "r0").set(GATE, json("closed"), 2).join());
        assertEquals(new FlagWrite.Written(1), channel(pool.pool(), "r2").set(GATE, json("open"), 0).join(),
                "another region's key of the same name is another row");
        assertEquals(3, version("r0", GATE));
        assertEquals(7, version("r1", GATE));
        assertEquals(1, version("r2", GATE));
    }

    /** {@code FlagWrite.Failed}: a value the table refuses, or a database that does not answer; never retried. */
    @Test
    void aWriteTheTableRefusesOrThatCannotReachTheDatabaseFails() throws SQLException {
        final WorldFlagsImpl flags = channel(pool.pool(), "r0");

        assertInstanceOf(FlagWrite.Failed.class, flags.set(GATE, json("x".repeat(4_100)), 0).join(),
                "a value over 4096 bytes");
        assertInstanceOf(FlagWrite.Failed.class, flags.set("Quest Gate", json("open"), 0).join(),
                "a key that is not <namespace>:<name> in lower case");
        assertEquals(0, version("r0", GATE));

        final Unreachable down = new Unreachable();
        final FlagWrite failed = channel(down, "r0").set(GATE, json("open"), 0).join();
        assertInstanceOf(FlagWrite.Failed.class, failed);
        assertEquals(1, down.connections.get(), "a failed write is not retried");
        assertEquals(0, version("r0", GATE));
    }

    /** A database every connection to fails, counting the tries. */
    static final class Unreachable implements DataSource {
        final AtomicInteger connections = new AtomicInteger();

        @Override
        public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            throw new SQLException("the test cut the database off");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
