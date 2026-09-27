package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heartbeat of a shard in the directory mode against a real PostgreSQL ({@code docs/DESIGN-M3.md}
 * {@code API-M3-010}, §19 「心跳按时写入，过期或关门的频道不被选中」, the half of {@link ShardHeartbeat}):
 * the row written at once and every 5 s, what it copies from the directory and the connection gate,
 * which rows the reads call live, and the stop.
 */
@Timeout(60)
class ShardHeartbeatDbTest {

    private static final String BUILD_ID = "synth-0123456789ab-p776";
    private static final ShardDirectory DIRECTORY = new ShardDirectory("synth", List.of(
            new ShardDirectory.Region("r0", new SynxManifest.Bounds(0, 0, 127, 127), List.of()),
            new ShardDirectory.Region("r1", new SynxManifest.Bounds(128, 0, 255, 127), List.of())),
            List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "10.0.0.7", 25601, 200)), "r0-c1");

    private TestDatabase database;
    private Database pool;
    private ServerProcess process;
    private final List<ShardHeartbeat> heartbeats = new ArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
    }

    @AfterEach
    void drop() {
        for (ShardHeartbeat heartbeat : heartbeats) heartbeat.close();
        if (process != null) process.stop();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private ShardHeartbeat heartbeat(DataSource dataSource) {
        final ShardHeartbeat heartbeat = new ShardHeartbeat(dataSource, DIRECTORY, BUILD_ID, process);
        heartbeats.add(heartbeat);
        return heartbeat;
    }

    /** The row of this shard as it is in the table. */
    private record Row(String mapId, String regionId, int channel, String host, int port, String buildId, int online,
                       int capacity, boolean accepting, Timestamp startedAt, Timestamp updatedAt) {
    }

    private @Nullable Row row() throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement("SELECT map_id, region_id, channel, host, port, "
                     + "build_id, online, capacity, accepting, started_at, updated_at FROM shard_heartbeat WHERE shard_id = ?")) {
            select.setString(1, "r0-c1");
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) return null;
                return new Row(result.getString(1), result.getString(2), result.getInt(3), result.getString(4),
                        result.getInt(5), result.getString(6), result.getInt(7), result.getInt(8), result.getBoolean(9),
                        result.getTimestamp(10), result.getTimestamp(11));
            }
        }
    }

    private int rows() throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement count = connection.prepareStatement("SELECT count(*) FROM shard_heartbeat");
             ResultSet result = count.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean writingThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals(ShardHeartbeat.THREAD));
    }

    /** Whether the writing thread is gone within a second, the time it takes a thread to end after its pool. */
    private static boolean writingThreadEnds() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (writingThreadAlive()) {
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(20);
        }
        return true;
    }

    /**
     * §19: read every 500 ms after start, three different updated_at within 12 s, the first one at the
     * start; after the login gate closes the next row is not accepting.
     */
    @Test
    void theFirstRowIsWrittenAtStartThenOneEveryFiveSeconds() throws Exception {
        final ConnectionGate gate = process.connection().gate();
        assertTrue(gate.takeSeat(gate.untracked()));
        assertTrue(gate.takeSeat(gate.untracked()));
        final ShardHeartbeat heartbeat = heartbeat(pool.pool());
        assertNull(row(), "building it writes nothing");

        final long started = System.nanoTime();
        heartbeat.start();
        final Set<Timestamp> written = new LinkedHashSet<>();
        final List<Row> seen = new ArrayList<>();
        long firstMillis = -1;
        while (written.size() < 3 && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(12)) {
            Thread.sleep(500);
            final Row row = row();
            if (row == null) continue;
            if (firstMillis < 0) firstMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (written.add(row.updatedAt())) seen.add(row);
        }
        assertEquals(3, written.size(), "three writes within 12 s: " + written);
        assertTrue(firstMillis <= 2_000, "the first row is written at the start, read after " + firstMillis + " ms");
        final Row first = seen.getFirst();
        assertEquals(new Row("synth", "r0", 1, "10.0.0.7", 25601, BUILD_ID, 2, 200, true, first.startedAt(),
                first.updatedAt()), first);
        for (Row row : seen) assertEquals(first.startedAt(), row.startedAt(), "one started_at for the whole process");
        assertEquals(1, rows());

        gate.close();
        final Timestamp last = seen.getLast().updatedAt();
        Row next = row();
        final long closed = System.nanoTime();
        while (next != null && next.updatedAt().equals(last) && System.nanoTime() - closed < TimeUnit.SECONDS.toNanos(8)) {
            Thread.sleep(250);
            next = row();
        }
        assertNotNull(next);
        assertFalse(next.updatedAt().equals(last), "written again after the gate closed");
        assertFalse(next.accepting(), "a closed login gate is written as not accepting");
    }

    private void insert(String shardId, String mapId, String regionId, int channel, int online, boolean accepting,
                        int secondsAgo) {
        database.execute("INSERT INTO shard_heartbeat (shard_id, map_id, region_id, channel, host, port, build_id, online, "
                + "capacity, accepting, started_at, updated_at) VALUES ('" + shardId + "', '" + mapId + "', '" + regionId
                + "', " + channel + ", 'host-" + shardId + "', " + (25600 + channel) + ", '" + BUILD_ID + "', " + online
                + ", 200, " + accepting + ", now() - interval '1 hour', now() - interval '" + secondsAgo + " seconds')");
    }

    /** A row is live while it is less than 15 s old by the database clock; both reads keep to the map. */
    @Test
    void theReadsTellLiveRowsFromExpiredOnesAndKeepToTheirMap() throws Exception {
        insert("r1-c1", "synth", "r1", 1, 0, true, 20);
        insert("r1-c2", "synth", "r1", 2, 10, false, 0);
        insert("r1-c3", "synth", "r1", 3, 120, true, 0);
        insert("r1-c4", "synth", "r1", 4, 5, true, 16);
        insert("r1-c5", "synth", "r1", 5, 5, true, 14);
        insert("r2-c1", "synth", "r2", 1, 0, true, 0);
        insert("x1-c1", "other", "r1", 1, 0, true, 0);
        final ShardHeartbeat heartbeat = heartbeat(pool.pool());

        final List<HeartbeatRow> region = new ArrayList<>(heartbeat.region("synth", "r1").get(5, TimeUnit.SECONDS));
        region.sort(Comparator.comparing(HeartbeatRow::shardId));
        assertEquals(List.of(
                new HeartbeatRow("r1-c1", "r1", 1, "host-r1-c1", 25601, 0, 200, true, false),
                new HeartbeatRow("r1-c2", "r1", 2, "host-r1-c2", 25602, 10, 200, false, true),
                new HeartbeatRow("r1-c3", "r1", 3, "host-r1-c3", 25603, 120, 200, true, true),
                new HeartbeatRow("r1-c4", "r1", 4, "host-r1-c4", 25604, 5, 200, true, false),
                new HeartbeatRow("r1-c5", "r1", 5, "host-r1-c5", 25605, 5, 200, true, true)), region);
        assertEquals(List.of(), heartbeat.region("other-map", "r1").get(5, TimeUnit.SECONDS));

        assertEquals(new HeartbeatRow("r1-c3", "r1", 3, "host-r1-c3", 25603, 120, 200, true, true),
                heartbeat.shard("synth", "r1-c3").get(5, TimeUnit.SECONDS));
        assertNull(heartbeat.shard("synth", "x1-c1").get(5, TimeUnit.SECONDS), "a row of another map is none");
        assertNull(heartbeat.shard("synth", "r9-c1").get(5, TimeUnit.SECONDS));
        assertEquals(7, rows(), "reading writes nothing");
    }

    @Test
    void aReadThatFailsCompletesExceptionally() {
        final ShardHeartbeat heartbeat = heartbeat(pool.pool());
        pool.close();
        final Throwable failed = assertFailed(() -> heartbeat.region("synth", "r1").get(10, TimeUnit.SECONDS));
        assertTrue(failed.getCause() instanceof SQLException, String.valueOf(failed.getCause()));
        final Throwable failedShard = assertFailed(() -> heartbeat.shard("synth", "r1-c1").get(10, TimeUnit.SECONDS));
        assertTrue(failedShard.getCause() instanceof SQLException, String.valueOf(failedShard.getCause()));
    }

    private static Throwable assertFailed(Executable read) {
        return assertThrows(ExecutionException.class, read);
    }

    /** A stop begun between the bind and step (7) runs close first; the start that follows does nothing. */
    @Test
    void aStartAfterCloseWritesNothingAndStartsNoThread() throws Exception {
        final ShardHeartbeat heartbeat = heartbeat(pool.pool());
        heartbeat.close();
        heartbeat.start();
        Thread.sleep(1_500);
        assertEquals(0, rows());
        assertFalse(writingThreadAlive(), "no writing thread");
    }

    @Test
    void closeStopsTheWritingThread() throws Exception {
        final ShardHeartbeat heartbeat = heartbeat(pool.pool());
        heartbeat.start();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (row() == null && System.nanoTime() < deadline) Thread.sleep(100);
        assertNotNull(row());
        assertTrue(writingThreadAlive(), "a platform thread writes");

        heartbeat.close();

        assertTrue(writingThreadEnds(), "the writing thread ends");
        final Row stopped = Objects.requireNonNull(row());
        assertTrue(stopped.accepting(), "no last row is written");
    }

    /**
     * A write that fails is only a WARN, one for every three in a row ({@code RB-M3-004}), and the next
     * write goes on as if nothing happened.
     */
    @Test
    void failedWritesWarnOnceEveryThreeInARowAndTheNextOneWrites() throws Exception {
        final AtomicBoolean cutOff = new AtomicBoolean(true);
        final DataSource real = pool.pool();
        final DataSource flaky = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (_, method, arguments) -> {
                    if (cutOff.get() && method.getName().equals("getConnection"))
                        throw new SQLException("the test cut the database off");
                    try {
                        return method.invoke(real, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        final ShardHeartbeat heartbeat = heartbeat(flaky);
        final Logger logger = (Logger) LoggerFactory.getLogger(ShardHeartbeat.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            heartbeat.beat();
            heartbeat.beat();
            assertEquals(List.of(), warnings(appender), "two failures in a row are not reported yet");
            heartbeat.beat();
            assertEquals(1, warnings(appender).size(), warnings(appender).toString());
            assertTrue(warnings(appender).getFirst().contains("shard_id=r0-c1"), warnings(appender).toString());
            heartbeat.beat();
            heartbeat.beat();
            assertEquals(1, warnings(appender).size(), "the fourth and fifth are part of the next three");
            heartbeat.beat();
            assertEquals(2, warnings(appender).size(), warnings(appender).toString());

            cutOff.set(false);
            heartbeat.beat();
            assertNotNull(row(), "written once the database is back");
            cutOff.set(true);
            heartbeat.beat();
            heartbeat.beat();
            assertEquals(2, warnings(appender).size(), "a write in between starts the count again");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
