package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.permission.RoleCommand;
import io.github.bearisbug.synaxis.app.permission.RoleStore;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who may hand out which role ({@code docs/DESIGN-PROD.md} {@code API-PROD-010}, §28.2): a player
 * grants or takes back only a role whose every node its own nodes cover, a {@code *} role only
 * with {@code *} itself; the console is not restricted.
 */
@EnvTest
class RoleEscalationIntegrationTest {

    private TestDatabase database;
    private Database pool;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        database.execute("INSERT INTO role (role_id) VALUES ('granter'), ('helper'), ('gm'), ('mod'), ('ops'), ('root');"
                + "INSERT INTO role_permission (role_id, node) VALUES ('granter', 'gm.role'), ('helper', 'gm.role'),"
                + " ('gm', 'gm.*'), ('mod', 'gm.ban'), ('ops', 'gm.*'), ('ops', 'synaxis.command.stop'), ('root', '*')");
    }

    @AfterEach
    void drop() {
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    @Test
    void aPlayerOnlyHandsOutRolesItsOwnNodesCover(Env env) {
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(pool.pool()),
                RedisLink.disabled("escalation"), HostPermissions.REFRESH);
        final EventNode<Event> node = EventNode.all("permissions-escalation");
        env.process().eventHandler().addChild(node);
        permissions.attach(node);
        final List<String> results = new CopyOnWriteArrayList<>();
        env.process().command().register(new RoleCommand(env.process(), permissions,
                (_, attributes) -> results.add(attributes.get("command") + " -> " + attributes.get("result"))));
        final Instance instance = env.createFlatInstance();
        final UUID granterId = UUID.randomUUID();
        final UUID gmId = UUID.randomUUID();
        final UUID rootId = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + granterId + "', 'granter'), ('"
                + gmId + "', 'gm'), ('" + rootId + "', 'root')");
        final var granterConnection = env.createConnection(new GameProfile(granterId, "Granter"));
        final Player granter = granterConnection.connect(instance, new Pos(0, 41, 0));
        permissions.load(granter);
        final var granterAnswers = granterConnection.trackIncoming(SystemChatPacket.class);
        final Player gm = env.createConnection(new GameProfile(gmId, "Gamemaster")).connect(instance, new Pos(0, 41, 0));
        permissions.load(gm);
        final Player root = env.createConnection(new GameProfile(rootId, "Root")).connect(instance, new Pos(0, 41, 0));
        permissions.load(root);

        final List<String> expected = List.of(
                "role grant " + granterId + " gm -> above_sender",
                "role grant " + target + " root -> above_sender",
                "role revoke " + gmId + " gm -> above_sender",
                "role grant " + target + " helper -> ok",
                "role grant " + target + " mod -> ok",
                "role grant " + target + " ops -> above_sender",
                "role grant " + gmId + " root -> above_sender",
                "role grant " + target + " nosuch -> unknown_role",
                "role grant " + target + " root -> ok",
                "role revoke " + target + " root -> ok");
        final ConsoleSender console = new ConsoleSender(env.process());
        final List<CommandSender> senders = List.of(granter, granter, granter, granter, gm, gm, gm, gm, root, console);
        for (int i = 0; i < expected.size(); i++) {
            final String input = expected.get(i).substring(0, expected.get(i).indexOf(" -> "));
            // A sender that lost gm.role on the way is refused by the precondition, without an event
            if (env.process().command().execute(senders.get(i), input).getType() != CommandResult.Type.SUCCESS)
                results.add(input + " -> precondition");
            final int count = i + 1;
            await(env, () -> results.size() == count, input);
        }

        assertEquals(expected, results);
        assertEquals("synaxis:role-above-sender", ((TranslatableComponent) granterAnswers.collect().getFirst().message()).key());
        assertEquals(Set.of("granter"), roleRows(granterId), "a player does not raise itself");
        assertEquals(Set.of("gm"), roleRows(gmId), "nor take back a role it could not grant");
        assertEquals(Set.of("helper", "mod"), roleRows(target), "the console takes back what only a * holder could");
    }

    private static void await(Env env, BooleanSupplier condition, String what) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within 5 s: " + what);
            env.tick();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private Set<String> roleRows(UUID account) {
        final Set<String> roles = new TreeSet<>();
        try (var connection = database.connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT role_id FROM account_role WHERE account_uuid = '" + account + "'")) {
            while (rows.next()) roles.add(rows.getString(1));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        return roles;
    }
}
