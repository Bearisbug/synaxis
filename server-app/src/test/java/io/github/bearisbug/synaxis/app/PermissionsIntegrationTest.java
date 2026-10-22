package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.permission.RoleCommand;
import io.github.bearisbug.synaxis.app.permission.RoleStore;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roles and permission nodes ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-011}, {@code API-PROD-010},
 * §19 the two permission scenarios): the node decides whether the GM command runs and the permission
 * level does not, roles are read at login and reach a plugin, a change reaches another shard through
 * Redis within 5 s and without Redis within the refresh period, and every run of the command is one
 * {@code gm_command} event.
 */
@EnvTest
class PermissionsIntegrationTest {

    @TempDir
    Path workingDirectory;

    private TestDatabase database;
    private Database pool;
    private @Nullable EventLedger ledger;
    private final List<RedisLink> links = new ArrayList<>();
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        database.execute("INSERT INTO role (role_id) VALUES ('gm'), ('mod');"
                + "INSERT INTO role_permission (role_id, node) VALUES ('gm', 'gm.*'), ('gm', 'synaxis.command.kick'),"
                + " ('mod', 'gm.ban')");
    }

    @AfterEach
    void drop() {
        for (RedisLink link : links) link.close();
        if (ledger != null) ledger.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** The console, recording the translate key of every answer. */
    private static final class RecordingConsole extends ConsoleSender {
        final List<String> answers = new CopyOnWriteArrayList<>();

        RecordingConsole(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendMessage(Component message) {
            if (message instanceof TranslatableComponent translatable) answers.add(translatable.key());
        }
    }

    /** A plugin that lets every player into one match and remembers its context. */
    private static final class Probe implements SynaxisPlugin {
        private final Instance match;
        private @Nullable PluginContext context;

        Probe(Instance match) {
            this.match = match;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("probe", "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public void onEnable(PluginContext ctx) {
            this.context = ctx;
            ctx.admission().register(_ -> new Admission.Accept(match, 1));
        }

        @Override
        public void onDisable() {
        }
    }

    private HostPermissions shard(Env env, String name, boolean withRedis, Duration refresh) {
        final String shardId = name + "-" + run;
        final RedisLink link = withRedis ? RedisLink.connect(TestDatabase.redisUrl(), shardId) : RedisLink.disabled(shardId);
        links.add(link);
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(pool.pool()), link, refresh);
        final EventNode<Event> node = EventNode.all("permissions-" + shardId);
        env.process().eventHandler().addChild(node);
        permissions.attach(node);
        return permissions;
    }

    private static Player online(Env env, HostPermissions permissions, Instance instance, UUID account, String name) {
        final Player player = env.createConnection(new GameProfile(account, name)).connect(instance, new Pos(0, 41, 0));
        permissions.load(player);
        return player;
    }

    private static void await(Env env, BooleanSupplier condition, Duration within, String what) {
        final long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within " + within + ": " + what);
            env.tick();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void theNodeDecidesTheCommandAndThePermissionLevelDoesNot(Env env) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(pool.pool()),
                RedisLink.disabled("test"), HostPermissions.REFRESH);
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger,
                PlayerStore.inMemory(env.process(), ledger::record), null, permissions);
        final List<String> events = new CopyOnWriteArrayList<>();
        env.process().command().register(new RoleCommand(env.process(), permissions, (event, attributes) ->
                events.add(event + " " + attributes)));
        final Instance match = env.createFlatInstance();
        final Probe probe = new Probe(match);
        host.enable(List.of(probe), Set.of());
        final PluginContext context = probe.context;
        final Permissions seen = context == null ? null : context.permissions();
        assertSame(permissions, seen, "a plugin asks the host's roles");
        final AtomicReference<Boolean> atSpawn = new AtomicReference<>();
        env.process().eventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.getPlayer().getUsername().equals("Gamemaster"))
                atSpawn.set(permissions.has(event.getPlayer(), "gm.ban"));
        });

        final UUID gm = UUID.randomUUID();
        final UUID nobody = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + gm + "', 'gm')");
        final Player g = env.createConnection(new GameProfile(gm, "Gamemaster")).connect(match, new Pos(0, 41, 0));
        final Player n = env.createConnection(new GameProfile(nobody, "Nobody")).connect(match, new Pos(0, 41, 0));
        n.setPermissionLevel(4);

        assertEquals(true, atSpawn.get(), "the roles are read before the player enters the world");
        assertEquals(Set.of("gm"), permissions.roles(g));
        assertTrue(permissions.has(g, "gm.role"), "gm.* covers gm.role");
        assertTrue(permissions.has(g, "synaxis.command.kick"));
        assertFalse(permissions.has(g, "gm"), "gm.* covers what is under gm, not gm itself");
        assertFalse(permissions.has(g, "synaxis.command.stop"));
        assertFalse(permissions.has(n, "gm.role"), "permission level 4 is no role");
        assertEquals(Set.of(), permissions.roles(n));
        assertThrows(IllegalArgumentException.class, () -> permissions.has(g, "gm.*"), "a wildcard is not a question");
        assertThrows(IllegalArgumentException.class, () -> permissions.has(g, "GM.Ban"));

        final CommandResult denied = env.process().command().execute(n, "role grant " + target + " mod");
        assertEquals(CommandResult.Type.CANCELLED, denied.getType(), "the precondition refuses the player without the node");
        final CommandResult granted = env.process().command().execute(g, "role grant " + target + " mod");
        assertEquals(CommandResult.Type.SUCCESS, granted.getType());
        await(env, () -> events.size() == 1, Duration.ofSeconds(5), "the grant is recorded");
        assertEquals("gm_command {actor=player, actor_uuid=" + gm + ", command=role grant " + target
                + " mod, target=" + target + ", result=ok}", events.getFirst());
        assertEquals(Set.of("mod"), roleRows(target), "a role can be granted before its account ever logged in");

        host.disableAll();
    }

    @Test
    void theConsoleGrantsRevokesAndListsAndEveryRunIsOneEvent(Env env) {
        final HostPermissions permissions = shard(env, "a", false, HostPermissions.REFRESH);
        final List<String> events = new CopyOnWriteArrayList<>();
        final RoleCommand command = new RoleCommand(env.process(), permissions, (event, attributes) ->
                events.add(event + " " + attributes.get("actor") + " " + attributes.get("target") + " " + attributes.get("result")));
        env.process().command().register(command);
        final RecordingConsole console = new RecordingConsole(env.process());
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, permissions, instance, account, "Moderator");
        assertFalse(permissions.has(player, "gm.ban"));

        final List<String> answers = List.of("synaxis:role-granted", "synaxis:role-list", "synaxis:role-unknown",
                "synaxis:role-revoked", "synaxis:role-not-granted", "synaxis:role-no-target");
        final List<String> inputs = List.of("role grant Moderator mod", "role list " + account, "role grant Moderator admin",
                "role revoke " + account + " mod", "role revoke Moderator mod", "role grant Stranger mod");
        for (int i = 0; i < inputs.size(); i++) {
            env.process().command().execute(console, inputs.get(i));
            final int expected = i + 1;
            await(env, () -> console.answers.size() == expected, Duration.ofSeconds(5), inputs.get(i));
            if (i == 0) assertTrue(permissions.has(player, "gm.ban"), "this shard reads its own change at once");
            if (i == 3) assertFalse(permissions.has(player, "gm.ban"), "and its own revocation");
        }
        assertEquals(answers, console.answers);
        assertEquals(List.of("gm_command console " + account + " ok", "gm_command console " + account + " ok",
                "gm_command console " + account + " unknown_role", "gm_command console " + account + " ok",
                "gm_command console " + account + " not_granted", "gm_command console null no_target"), events);
    }

    @Test
    void aChangeReachesAnotherShardThroughRedisWithinFiveSeconds(Env env) throws Exception {
        final HostPermissions first = shard(env, "a", true, HostPermissions.REFRESH);
        final HostPermissions second = shard(env, "b", true, HostPermissions.REFRESH);
        final RecordingConsole console = new RecordingConsole(env.process());
        final RoleCommand command = new RoleCommand(env.process(), first, (_, _) -> { });
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + account + "', 'gm')");
        final Player player = online(env, second, instance, account, "Roamer");
        assertTrue(second.has(player, "gm.ban"));
        env.process().command().register(command);
        // Time for the listener threads of both shards to subscribe
        TimeUnit.MILLISECONDS.sleep(500);

        long start = System.nanoTime();
        env.process().command().execute(console, "role revoke " + account + " gm");
        await(env, () -> !second.has(player, "gm.ban"), Duration.ofSeconds(5), "the other shard drops gm.ban");
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis < 5_000, "within 5 s, took " + tookMillis + " ms");
        assertEquals(Set.of(), second.roles(player));

        start = System.nanoTime();
        env.process().command().execute(console, "role grant " + account + " mod");
        await(env, () -> second.has(player, "gm.ban"), Duration.ofSeconds(5), "the other shard gains gm.ban");
        tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis < 5_000, "within 5 s, took " + tookMillis + " ms");
        assertEquals(Set.of("mod"), second.roles(player));
    }

    @Test
    void withoutRedisTheRefreshCatchesUpWithinItsPeriod(Env env) {
        assertEquals(Duration.ofSeconds(30), HostPermissions.REFRESH, "§19: 30 s while Redis is unreachable");
        final HostPermissions second = shard(env, "b", false, Duration.ofSeconds(1));
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final UUID expired = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + account + "', 'gm');"
                + "INSERT INTO account_role (account_uuid, role_id, expires_at) VALUES ('" + expired
                + "', 'gm', now() - interval '1 second')");
        final Player player = online(env, second, instance, account, "Roamer");
        final Player lapsed = online(env, second, instance, expired, "Lapsed");
        assertTrue(second.has(player, "gm.ban"));
        assertFalse(second.has(lapsed, "gm.ban"), "an expired grant grants nothing");

        database.execute("DELETE FROM account_role WHERE account_uuid = '" + account + "'");
        await(env, () -> !second.has(player, "gm.ban"), Duration.ofSeconds(3), "the refresh drops gm.ban");

        database.execute("UPDATE account_role SET expires_at = NULL WHERE account_uuid = '" + expired + "'");
        await(env, () -> second.has(lapsed, "gm.ban"), Duration.ofSeconds(3), "the refresh adds gm.ban");
        lapsed.scheduleNextTick(Entity::remove);
        await(env, () -> second.roles(lapsed).isEmpty(), Duration.ofSeconds(3), "a player that left is forgotten");
    }

    /** A role that grants no node yet is held all the same, and covers nothing. */
    @Test
    void aRoleWithoutNodesIsHeldAndCoversNothing(Env env) {
        database.execute("INSERT INTO role (role_id) VALUES ('newcomer')");
        final HostPermissions permissions = shard(env, "a", false, HostPermissions.REFRESH);
        final UUID account = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + account + "', 'newcomer')");
        final Player player = online(env, permissions, env.createFlatInstance(), account, "Newcomer");

        assertEquals(Set.of("newcomer"), permissions.roles(player));
        assertFalse(permissions.has(player, "gm.ban"));
    }

    @Test
    void aLoginWhoseRolesCannotBeReadHoldsNoneUntilTheNextRead(Env env) {
        final AtomicBoolean down = new AtomicBoolean(true);
        final DataSource flaky = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (_, method, arguments) -> {
                    if (down.get() && method.getName().equals("getConnection"))
                        throw new SQLTransientConnectionException("the database is down");
                    try {
                        return method.invoke(pool.pool(), arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(flaky),
                RedisLink.disabled("flaky"), Duration.ofSeconds(1));
        final EventNode<Event> node = EventNode.all("permissions-flaky");
        env.process().eventHandler().addChild(node);
        permissions.attach(node);
        final UUID account = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + account + "', 'gm')");
        final Player player = online(env, permissions, env.createFlatInstance(), account, "Unlucky");
        assertFalse(permissions.has(player, "gm.ban"), "a role that cannot be read is not held");

        down.set(false);
        await(env, () -> permissions.has(player, "gm.ban"), Duration.ofSeconds(3), "the next refresh reads it");
    }

    @Test
    void withoutADatabaseNobodyHoldsANodeAndTheCommandSaysSo(Env env) {
        final HostPermissions permissions = HostPermissions.withoutDatabase(env.process());
        permissions.attach(EventNode.all("unused"));
        final RecordingConsole console = new RecordingConsole(env.process());
        final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
        env.process().command().register(new RoleCommand(env.process(), permissions, (_, attributes) -> events.add(attributes)));
        final Player player = online(env, permissions, env.createFlatInstance(), UUID.randomUUID(), "Anyone");
        assertFalse(permissions.has(player, "gm.ban"));
        assertEquals(Set.of(), permissions.roles(player));

        env.process().command().execute(console, "role grant Anyone gm");
        await(env, () -> !console.answers.isEmpty(), Duration.ofSeconds(5), "the answer");
        assertEquals(List.of("synaxis:role-unavailable"), console.answers);
        // The command answers first and records its gm_command line after it returns
        await(env, () -> !events.isEmpty(), Duration.ofSeconds(5), "the ledger line");
        assertEquals("unavailable", events.getFirst().get("result"));
    }

    @Test
    void aNodeOutsideTheGrammarIsRefusedByTheDatabase() {
        final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> database.execute(
                "INSERT INTO role_permission (role_id, node) VALUES ('gm', 'GM.Ban')"));
        assertTrue(refused.getCause().getMessage().contains("role_permission_node_check"), refused.getCause().getMessage());
        database.execute("INSERT INTO role_permission (role_id, node) VALUES ('gm', '*'), ('gm', 'a-b.c_d.*')");
    }

    @Test
    void aRevocationWhileALoginReadsTheRolesIsNotLost(Env env) throws Exception {
        final AtomicBoolean hold = new AtomicBoolean();
        final CountDownLatch rowsRead = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        // The first connection taken once armed keeps its rows but is not closed until released
        final DataSource gated = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (_, method, arguments) -> {
                    final Object result;
                    try {
                        result = method.invoke(pool.pool(), arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (!method.getName().equals("getConnection") || !hold.compareAndSet(true, false)) return result;
                    final Connection connection = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (_, call, callArguments) -> {
                                if (call.getName().equals("close")) {
                                    rowsRead.countDown();
                                    release.await();
                                }
                                try {
                                    return call.invoke(connection, callArguments);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                });
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(gated),
                RedisLink.disabled("gated"), HostPermissions.REFRESH);
        final EventNode<Event> node = EventNode.all("permissions-gated");
        env.process().eventHandler().addChild(node);
        permissions.attach(node);
        env.process().command().register(new RoleCommand(env.process(), permissions, (_, _) -> { }));
        final RecordingConsole console = new RecordingConsole(env.process());
        final UUID account = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + account + "', 'gm')");
        final Player player = env.createConnection(new GameProfile(account, "Racer")).connect(env.createFlatInstance(),
                new Pos(0, 41, 0));

        hold.set(true);
        final Thread login = Thread.ofVirtual().start(() -> permissions.load(player));
        assertTrue(rowsRead.await(5, TimeUnit.SECONDS), "the login read its rows");
        env.process().command().execute(console, "role revoke " + account + " gm");
        await(env, () -> console.answers.size() == 1, Duration.ofSeconds(5), "the revocation");
        assertEquals(List.of("synaxis:role-revoked"), console.answers);
        release.countDown();
        assertTrue(login.join(Duration.ofSeconds(5)), "the login ends");
        assertFalse(permissions.has(player, "gm.ban"), "the login's older rows do not bring the revoked role back");
        assertEquals(Set.of(), permissions.roles(player));
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
