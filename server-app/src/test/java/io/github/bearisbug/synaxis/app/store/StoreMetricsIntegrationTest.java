package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.app.metrics.HostMetrics;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store half of {@code docs/DESIGN-PROD.md} {@code API-PROD-015} against a real PostgreSQL:
 * {@code synaxis_db_statement_seconds} per statement, {@code synaxis_store_save_total} per outcome,
 * the pool gauges and {@code synaxis_shard_session_state}, read back from the endpoint's text.
 */
@EnvTest
class StoreMetricsIntegrationTest {

    private TestDatabase database;
    private Database pool;
    private StoreFixture.Shard shard;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        if (shard != null) shard.store().close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    @Test
    void theStatementsAndTheSavesOfTheStoreAreCountedByOutcome(Env env) throws SQLException {
        shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"), StoreFixture.FAST);
        try (HostMetrics metrics = HostMetrics.register(env.process(), new HostMetrics.Sources(() -> 0,
                shard.store(), shard.session(), pool, RedisLink.disabled("r1-c1")))) {
            final Instance instance = env.createFlatInstance();
            final Player seller = online(env, instance, "Seller");
            final Player buyer = online(env, instance, "Buyer");

            shard.store().get(seller, PURSE).coins = 3;
            shard.store().markDirty(seller, PURSE);
            assertEquals(SaveResult.SAVED, shard.store().saveNow(seller).join());
            shard.session().renewOrReopen();
            assertEquals(SaveResult.SAVED, trade(seller, buyer, 10));
            database.execute("UPDATE character SET version = version + 1 WHERE account_uuid = '" + buyer.getUuid() + "'");
            assertEquals(SaveResult.FENCED, trade(seller, buyer, 11));

            final String text = metrics.scrape();
            assertEquals(2, value(text, "synaxis_db_statement_seconds_count{op=\"load\"}"), "two logins");
            assertEquals(1, value(text, "synaxis_db_statement_seconds_count{op=\"save\"}"), "one saveNow");
            assertEquals(1, value(text, "synaxis_db_statement_seconds_count{op=\"renew\"}"), "one renewal");
            assertEquals(2, value(text, "synaxis_db_statement_seconds_count{op=\"commit\"}"), "two commits");
            assertEquals(2, value(text, "synaxis_store_save_total{result=\"saved\"}"), "the saveNow and a commit");
            assertEquals(1, value(text, "synaxis_store_save_total{result=\"fenced\"}"), "the commit of a stale version");
            assertEquals(0, value(text, "synaxis_store_save_total{result=\"unavailable\"}"));
            assertEquals(1, value(text, "synaxis_shard_session_state{state=\"active\"}"));
            assertEquals(0, value(text, "synaxis_shard_session_state{state=\"degraded\"}"));
            assertEquals(0, value(text, "synaxis_db_pool_active"), "no statement is running");
            assertEquals(0, value(text, "synaxis_db_pool_pending"));
            assertEquals(0, value(text, "synaxis_redis_up"), "no Redis");
        }
    }

    private Player online(Env env, Instance instance, String name) {
        final Player player = connect(env.process(), UUID.randomUUID(), name);
        assertTrue(shard.store().load(player), "loaded");
        shard.store().admitted(player);
        spawn(env.process(), player, instance);
        return player;
    }

    private SaveResult trade(Player seller, Player buyer, int coins) {
        final StoreFixture.Purse paid = new StoreFixture.Purse();
        paid.coins = coins;
        return shard.store().commitTogether(Map.of(seller, Map.<DataSection<?>, Object>of(PURSE, paid),
                buyer, Map.<DataSection<?>, Object>of(PURSE, paid)), () -> {
        }).join();
    }

    /** The value of one series in the Prometheus text; a missing series fails the test. */
    private static long value(String text, String series) {
        return text.lines().filter(line -> line.startsWith(series + " ")).findFirst()
                .map(line -> (long) Double.parseDouble(line.substring(series.length() + 1)))
                .orElseThrow(() -> new AssertionError("no " + series + " in\n" + text));
    }
}
