package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.EnchantmentList;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.RESPAWN;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.leave;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.online;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.play;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.slots;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code synaxis:player} on PostgreSQL ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-047}, {@code API-PROD-032}): the
 * state survives a restart of the shard, goes with the write of a transfer, refuses a login it cannot be decoded for,
 * and is not written for a player that leaves before its first spawn.
 */
@EnvTest
class PlayerStatePostgresIntegrationTest {

    private static final PositionRule MAP = new PositionRule.MapShard("demo", "b1", new SynxManifest.Bounds(0, 0, 3, 3));

    private TestDatabase database;
    private Database pool;
    private final List<StoreFixture.Shard> shards = new ArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        for (StoreFixture.Shard shard : shards) shard.store().close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** A shard's store with {@code synaxis:player} installed, the autosave out of the way. */
    private record Shard(StoreFixture.Shard store, PlayerStateSection section) {
    }

    private Shard shard(Env env, String shardId, PositionRule rule) throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, false, RedisLink.disabled(shardId),
                PlayerStore.Options.DEFAULT);
        shards.add(shard);
        return new Shard(shard, PlayerStateFixture.install(env, shard.store(), shard.node(), rule));
    }

    /** Takes a shard out of the process, as its process ending would. */
    private void stop(Env env, Shard shard) {
        env.process().eventHandler().removeChild(shard.store().node());
        shards.remove(shard.store());
        shard.store().store().close();
    }

    @Test
    void aRestartedShardGivesThePlayerBackItsState(Env env) throws SQLException {
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Pos leftAt = new Pos(8.5, 40, -3.5, 45, 10);
        final Map<Integer, ItemStack> inventory = Map.of(
                0, ItemStack.of(Material.STONE, 63),
                1, ItemStack.builder(Material.IRON_PICKAXE).set(DataComponents.DAMAGE, 3).build(),
                3, ItemStack.builder(Material.IRON_SWORD)
                        .set(DataComponents.ENCHANTMENTS, new EnchantmentList(RegistryKey.unsafeOf("minecraft:sharpness"), 5))
                        .build(),
                9, ItemStack.of(Material.DIRT, 9));
        final Shard before = shard(env, "r1-c1", new PositionRule.Core());
        final Player first = online(env, before.store().store(), instance, account, "Restarter");
        play(first, leftAt, 2, 13, inventory);
        leave(env, before.store().store(), first);
        assertNotNull(section(account), "the logout wrote synaxis:player");
        stop(env, before);

        final Shard after = shard(env, "r1-c1", new PositionRule.Core());
        final Player again = online(env, after.store().store(), instance, account, "Restarter");
        env.tick();

        assertEquals(leftAt, again.getPosition());
        assertEquals(2, again.getHeldSlot());
        assertEquals(13f, again.getHealth());
        assertEquals(slots(inventory), slots(again), "the 46 slots, the enchanted sword included");
        assertEquals(1, after.store().store().versionOf(again), "written once, by the logout");
    }

    /**
     * The write of a transfer carries the section, the ticket only its version; the target applies the inventory and
     * the health, and leaves the player at the arrival point its respawn point was set to.
     */
    @Test
    void aTransferCarriesTheSectionToTheTargetShard(Env env) throws SQLException {
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Shard source = shard(env, "r0-c1", MAP);
        final Player leaving = online(env, source.store().store(), instance, account, "Traveller");
        play(leaving, new Pos(40.5, 40, 40.5), 0, 15, Map.of(0, ItemStack.of(Material.DIRT, 5)));

        final CompletableFuture<PlayerStore.@Nullable Handoff> handoff = source.store().store().transferOut(leaving);
        assertNotNull(handoff, "online on the source");
        await(env, handoff::isDone, "the write of the transfer");
        final PlayerStore.Handoff written = handoff.join();
        assertNotNull(written, "the transfer was written");
        final String stored = section(account);
        assertNotNull(stored, "synaxis:player in the transfer's write");
        assertTrue(stored.contains("\"minecraft:dirt\"") && stored.contains("\"health\": 15"), stored);
        leaving.getPlayerConnection().disconnect();
        await(env, () -> source.store().store().versionOf(leaving) == -1, "the source lets the player go");
        stop(env, source);

        final Shard target = shard(env, "r1-c1", MAP);
        final Player arriving = connect(env.process(), account, "Traveller");
        assertTrue(target.store().store().load(arriving));
        target.section().claimed(arriving);
        assertTrue(target.store().store().admitted(arriving));
        spawn(env.process(), arriving, instance);
        env.tick();

        assertEquals(written.version(), target.store().store().versionOf(arriving), "the version the ticket carries");
        assertEquals(RESPAWN, arriving.getPosition(), "the arrival point, not the position saved on the source");
        assertEquals(slots(Map.of(0, ItemStack.of(Material.DIRT, 5))), slots(arriving));
        assertEquals(15f, arriving.getHealth());
    }

    @Test
    void anUndecodableSectionRefusesTheLoginAndStaysAsItIs(Env env) throws SQLException {
        final Shard shard = shard(env, "r1-c1", new PositionRule.Core());
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player first = online(env, shard.store().store(), instance, account, "Broken");
        leave(env, shard.store().store(), first);
        final StoreFixture.Row row = row(database, account);
        assertNotNull(row);
        final List<String> broken = List.of(
                PlayerStateFixture.saved("{\"kind\": \"core\"}", RESPAWN).replace("minecraft:dirt", "minecraft:no_such_item"),
                PlayerStateFixture.saved("{\"kind\": \"core\"}", RESPAWN).replace("\"held_slot\": 4", "\"held_slot\": 9"));

        for (String json : broken) {
            database.execute("UPDATE character_section SET data = '" + json + "'::jsonb WHERE section_id = '"
                    + PlayerStateSection.ID + "' AND character_id = " + row.characterId());
            final String stored = section(account);
            final Player player = connect(env.process(), account, "Broken");

            assertFalse(shard.store().store().load(player), json);
            assertEquals(List.of(DisconnectKeys.STATE_UNAVAILABLE), kicks(player), json);
            assertEquals(stored, section(account), "the stored section is left as it is");
            assertEquals(row.version(), row(database, account).version(), "no write");
        }
    }

    /**
     * Between the admission and the first spawn nothing of {@code synaxis:player} is written: the returning
     * character's row stays as it is, the new character gets none. The logout save still gives the lease back, which
     * bumps the version as every logout does.
     */
    @Test
    void aLogoutBeforeTheFirstSpawnWritesNoSection(Env env) throws SQLException {
        final Shard shard = shard(env, "r1-c1", new PositionRule.Core());
        final Instance instance = env.createFlatInstance();
        final UUID returning = UUID.randomUUID();
        final UUID newcomer = UUID.randomUUID();
        final Player first = online(env, shard.store().store(), instance, returning, "Hasty");
        play(first, new Pos(8.5, 40, -3.5), 0, 20, Map.of(0, ItemStack.of(Material.STONE, 63)));
        leave(env, shard.store().store(), first);
        final String saved = section(returning);
        assertNotNull(saved);

        for (UUID account : new UUID[]{returning, newcomer}) {
            final Player player = connect(env.process(), account, "Hasty");
            assertTrue(shard.store().store().load(player));
            assertTrue(shard.store().store().admitted(player));
            final long version = shard.store().store().versionOf(player);
            player.getPlayerConnection().disconnect();
            await(env, () -> {
                try {
                    final StoreFixture.Row row = row(database, account);
                    return row != null && row.lease() == null;
                } catch (SQLException e) {
                    throw new AssertionError(e);
                }
            }, "the logout save gives the lease back");
            assertEquals(version + 1, row(database, account).version(), "the logout save of " + account);
        }

        assertEquals(saved, section(returning), "the returning character's synaxis:player");
        assertNull(section(newcomer), "a synaxis:player for the new character");
    }

    private @Nullable String section(UUID account) throws SQLException {
        try (Connection connection = database.connect(); PreparedStatement select = connection.prepareStatement("""
                SELECT s.data::text FROM character c JOIN character_section s ON s.character_id = c.character_id
                 WHERE c.account_uuid = ? AND s.section_id = ?""")) {
            select.setObject(1, account);
            select.setString(2, PlayerStateSection.ID);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }
}
