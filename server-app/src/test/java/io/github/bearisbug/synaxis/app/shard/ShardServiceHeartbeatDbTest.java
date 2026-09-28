package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.app.transfer.TransferTickets;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The half of §19 「心跳按时写入，过期或关门的频道不被选中」 ({@code docs/DESIGN-M3.md}
 * {@code REQ-M3-016}) that goes through {@link ShardService}: a {@link ShardServiceImpl} reading the
 * {@code shard_heartbeat} rows of a real PostgreSQL through {@link ShardHeartbeat}, and choosing only
 * a channel whose row is fresh and accepting. The writing half is {@link ShardHeartbeatDbTest}'s.
 */
@Timeout(60)
class ShardServiceHeartbeatDbTest {

    /** The source side of a transfer, recording where it was asked to send the player. */
    private static final class Tickets implements TransferTickets {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<TransferOutcome> transfer(Player player, String targetShardId, String host, int port) {
            sent.add(targetShardId + " " + host + ":" + port);
            return CompletableFuture.completedFuture(TransferOutcome.SENT);
        }
    }

    private TestDatabase database;
    private Database pool;
    private Env env;
    private ShardHeartbeat heartbeat;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        env = Env.create(UnaryOperator.identity());
    }

    @AfterEach
    void drop() {
        if (heartbeat != null) heartbeat.close();
        if (env != null) env.process().stop();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** A row of region r1 of the map synth, {@code age} seconds old by the database clock. */
    private void insert(String shardId, int channel, String host, int port, int online, boolean accepting, int age) {
        database.execute("INSERT INTO shard_heartbeat (shard_id, map_id, region_id, channel, host, port, build_id, "
                + "online, capacity, accepting, started_at, updated_at) VALUES ('" + shardId + "', 'synth', 'r1', "
                + channel + ", '" + host + "', " + port + ", 'synth-0123456789ab-p776', " + online + ", 200, " + accepting
                + ", now() - interval '1 hour', now() - interval '" + age + " seconds')");
    }

    @Test
    void anExpiredOrClosedChannelIsNotChosenAndAStaleOneMakesTheRegionOffline() throws Exception {
        // The addresses are the rows', not the directory's r1-c1 at 127.0.0.1:25602
        insert("r1-c1", 1, "10.0.1.1", 25611, 0, true, 20);
        insert("r1-c2", 2, "10.0.1.2", 25612, 0, false, 0);
        insert("r1-c3", 3, "10.0.1.3", 25613, 120, true, 0);
        final ShardDirectory directory = ExampleDirectory.as("r0-c1");
        heartbeat = new ShardHeartbeat(pool.pool(), directory, "synth-0123456789ab-p776", env.process());
        final Tickets tickets = new Tickets();
        final ShardService service = new ShardServiceImpl(directory, heartbeat, tickets);
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));

        assertEquals(ShardTransferResult.SENT, service.transfer(player, "r1").get(5, TimeUnit.SECONDS));
        assertEquals(List.of("r1-c3 10.0.1.3:25613"), tickets.sent,
                "r1-c1 is 20 s old and r1-c2 is closed, though both are emptier: r1-c3 at the address of its row");

        database.execute("UPDATE shard_heartbeat SET updated_at = now() - interval '16 seconds' WHERE shard_id = 'r1-c3'");
        assertEquals(ShardTransferResult.OFFLINE, service.transfer(player, "r1").get(5, TimeUnit.SECONDS),
                "16 s is past the 15 s a row stays live");
        assertEquals(ShardTransferResult.OFFLINE, service.transferTo(player, "r1-c3").get(5, TimeUnit.SECONDS));
        assertEquals(1, tickets.sent.size(), "the tickets are not reached again, so no cooldown is taken");
    }
}
