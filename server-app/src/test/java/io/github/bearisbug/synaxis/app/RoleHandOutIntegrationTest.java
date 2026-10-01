package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.permission.RoleCommand;
import io.github.bearisbug.synaxis.app.permission.RoleStore;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a player holding {@code gm.role} hands out is judged by what the database says it holds when
 * it asks, not by what this shard read last ({@code docs/DESIGN-PROD.md} {@code API-PROD-010}, §28.2),
 * and runs out no later than the earliest of those grants: a grant in force keeps its expiry, one that
 * ran out takes the holder's, and a role the holder lost, run out or taken back, is not handed back to
 * it on what this shard read before. Redis is off and the refresh an hour away: no read comes unasked.
 */
@EnvTest
class RoleHandOutIntegrationTest {

    private TestDatabase database;
    private Database pool;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        database.execute("INSERT INTO role (role_id) VALUES ('helper'), ('mod');"
                + "INSERT INTO role_permission (role_id, node) VALUES ('helper', 'gm.role'), ('mod', 'gm.ban')");
    }

    @AfterEach
    void drop() {
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** The roles of this shard, with the role command; every run is told to the list as its result. */
    private record Shard(HostPermissions permissions, List<String> results) {
    }

    private Shard shard(Env env) {
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(pool.pool()),
                RedisLink.disabled("hand-out"), Duration.ofHours(1));
        final EventNode<Event> node = EventNode.all("permissions-hand-out");
        env.process().eventHandler().addChild(node);
        permissions.attach(node);
        final List<String> results = new CopyOnWriteArrayList<>();
        env.process().command().register(new RoleCommand(env.process(), permissions,
                (_, attributes) -> results.add(attributes.get("command") + " -> " + attributes.get("result"))));
        return new Shard(permissions, results);
    }

    private static Player online(Env env, Shard shard, UUID account, String name) {
        final Player player = env.createConnection(new GameProfile(account, name)).connect(env.createFlatInstance(),
                new Pos(0, 41, 0));
        shard.permissions().load(player);
        return player;
    }

    /** Runs a command as the player and waits for its event. */
    private static void run(Env env, Shard shard, Player player, String input) {
        final int before = shard.results().size();
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(player, input).getType(),
                "the shard still holds gm.role for the player: " + input);
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (shard.results().size() == before) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within 5 s: " + input);
            env.tick();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Every grant of an account, each role with whether it runs out, and if so whether it has. */
    private Map<String, String> grants(UUID account) {
        final Map<String, String> grants = new TreeMap<>();
        try (var connection = database.connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT role_id, CASE WHEN expires_at IS NULL THEN 'never'"
                     + " WHEN expires_at > now() THEN 'later' ELSE 'past' END FROM account_role WHERE account_uuid = '"
                     + account + "'")) {
            while (rows.next()) grants.put(rows.getString(1), rows.getString(2));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        return grants;
    }

    /** When each grant of an account runs out, as the database prints it. */
    private Map<String, String> expiries(UUID account) {
        final Map<String, String> expiries = new TreeMap<>();
        try (var connection = database.connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT role_id, coalesce(expires_at::text, 'never') FROM account_role"
                     + " WHERE account_uuid = '" + account + "'")) {
            while (rows.next()) expiries.put(rows.getString(1), rows.getString(2));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        return expiries;
    }

    @Test
    void aHolderHandsOutNothingThatOutlivesItsOwnGrants(Env env) {
        final Shard shard = shard(env);
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final UUID carol = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id, expires_at) VALUES ('" + alice + "', 'helper', NULL),"
                + " ('" + alice + "', 'mod', now() + interval '1 day'), ('" + carol + "', 'helper', NULL),"
                + " ('" + carol + "', 'mod', NULL)");
        final String until = expiries(alice).get("mod");
        final Player trial = online(env, shard, alice, "Alice");
        final Player keeper = online(env, shard, carol, "Carol");

        run(env, shard, trial, "role grant " + alice + " mod");
        assertEquals(Map.of("helper", "never", "mod", until), expiries(alice), "its own grant keeps its expiry");
        run(env, shard, keeper, "role grant " + alice + " mod");
        assertEquals(Map.of("helper", "never", "mod", until), expiries(alice),
                "nor does a holder whose grants never run out lift it");

        run(env, shard, trial, "role grant " + bob + " mod");
        run(env, shard, trial, "role grant " + bob + " helper");
        assertEquals(Map.of("helper", until, "mod", until), expiries(bob),
                "what it hands out runs out when its own grants start to");

        // Runs out with no read since: the shard still holds gm.ban for it
        database.execute("UPDATE account_role SET expires_at = now() - interval '1 second' WHERE account_uuid = '" + alice
                + "' AND role_id = 'mod'");
        run(env, shard, trial, "role grant " + alice + " mod");
        assertEquals(Map.of("helper", "never", "mod", "past"), grants(alice), "an expired grant stays expired");

        assertEquals(List.of(
                "role grant " + alice + " mod -> ok",
                "role grant " + alice + " mod -> ok",
                "role grant " + bob + " mod -> ok",
                "role grant " + bob + " helper -> ok",
                "role grant " + alice + " mod -> above_sender"), shard.results());
    }

    @Test
    void aRoleTakenBackIsNotHandedOutFromWhatTheShardReadBefore(Env env) {
        final Shard shard = shard(env);
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + alice + "', 'helper'), ('" + alice
                + "', 'mod')");
        final Player player = online(env, shard, alice, "Alice");

        // Taken back on another shard while Redis is down: this shard holds gm.role for it until the refresh
        database.execute("DELETE FROM account_role WHERE account_uuid = '" + alice + "' AND role_id = 'helper'");
        run(env, shard, player, "role grant " + alice + " helper");
        run(env, shard, player, "role grant " + bob + " mod");

        assertEquals(List.of(
                "role grant " + alice + " helper -> above_sender",
                "role grant " + bob + " mod -> above_sender"), shard.results());
        assertEquals(Map.of("mod", "never"), grants(alice), "the role taken back stays taken back");
        assertEquals(Map.of(), grants(bob), "and without gm.role it hands out nothing");
    }

    @Test
    void aGrantThatRanOutIsHandedOutAgainUntilTheHoldersOwnRunOut(Env env) {
        final Shard shard = shard(env);
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id, expires_at) VALUES ('" + alice + "', 'helper', NULL),"
                + " ('" + alice + "', 'mod', now() + interval '1 day'), ('" + bob + "', 'mod', now() - interval '1 day')");
        final String until = expiries(alice).get("mod");
        final Player player = online(env, shard, alice, "Alice");

        run(env, shard, player, "role grant " + bob + " mod");

        assertEquals(List.of("role grant " + bob + " mod -> ok"), shard.results());
        assertEquals(Map.of("mod", until), expiries(bob), "the grant that ran out runs again until Alice's own does");
    }
}
