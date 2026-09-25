package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.permission.RoleStore;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The commands of the host behind the roles of PostgreSQL ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-011}, {@code API-PROD-010}): a role granted or taken back while the player is
 * online changes what it may run and, at the same read, the commands among its completions.
 */
@EnvTest
class CommandRolesIntegrationTest {

    private TestDatabase database;
    private Database pool;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        database.execute("INSERT INTO role (role_id) VALUES ('warden');"
                + "INSERT INTO role_permission (role_id, node) VALUES ('warden', 'synaxis.command.kick')");
    }

    @AfterEach
    void drop() {
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private static Set<String> declared(DeclareCommandsPacket packet) {
        return packet.nodes().stream().map(node -> node.name).collect(Collectors.toSet());
    }

    /**
     * Makes a change, ticks through three refresh periods of 1 s and returns the names of the last
     * command tree the player got meanwhile.
     */
    private static Set<String> nextTree(Env env, TestConnection connection, Runnable change) {
        final Collector<DeclareCommandsPacket> tree = connection.trackIncoming(DeclareCommandsPacket.class);
        change.run();
        final long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() - end < 0) {
            env.tick();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        final List<DeclareCommandsPacket> sent = tree.collect();
        if (sent.isEmpty()) throw new AssertionError("no command tree within 3 s");
        return declared(sent.getLast());
    }

    @Test
    void aRoleGrantedOrTakenBackOnlineChangesTheCompletionsAtTheSameRead(Env env) {
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(pool.pool()),
                RedisLink.disabled("roles"), Duration.ofSeconds(1));
        final EventNode<Event> node = EventNode.all("command-roles");
        env.process().eventHandler().addChild(node);
        permissions.attach(node);
        new HostCommands(env.process(), permissions, PlayerStore.inMemory(env.process(), (_, _) -> { }),
                (_, _) -> { }, new Profiler(Path.of("profiles"), "test", Clock.systemUTC())).register();
        final UUID account = UUID.randomUUID();
        final TestConnection connection = env.createConnection(new GameProfile(account, "Warden"));
        final Collector<DeclareCommandsPacket> atSpawn = connection.trackIncoming(DeclareCommandsPacket.class);
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 41, 0));
        permissions.load(player);
        assertFalse(declared(atSpawn.collect().getLast()).contains("kick"));
        env.createConnection(new GameProfile(UUID.randomUUID(), "Target"))
                .connect(env.createFlatInstance(), new Pos(0, 41, 0));
        assertEquals(CommandResult.Type.CANCELLED, env.process().command().execute(player, "kick Target").getType());

        final Set<String> granted = nextTree(env, connection, () -> database.execute(
                "INSERT INTO account_role (account_uuid, role_id) VALUES ('" + account + "', 'warden')"));
        assertEquals(true, granted.contains("kick"), "the refresh that reads the role sends the tree: " + granted);
        assertFalse(granted.contains("stop"));
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(player, "kick Target").getType());

        final Set<String> revoked = nextTree(env, connection, () -> database.execute(
                "DELETE FROM account_role WHERE account_uuid = '" + account + "'"));
        assertFalse(revoked.contains("kick"), revoked.toString());
    }
}
