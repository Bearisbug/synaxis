package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.moderation.ModerationCommands;
import io.github.bearisbug.synaxis.app.moderation.ModerationStore;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
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
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.Ban;
import io.github.bearisbug.synaxis.plugin.BanTarget;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.Moderation;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bans and the whitelist ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-010}, {@code API-PROD-009},
 * §19 the three moderation scenarios): a banned account or network is turned away in the pre-login
 * event with the reason and the expiry, before the admission provider; a ban kicks the online target
 * within 5 s and writes {@code player_banned}; expired and lifted bans do not apply; the whitelist
 * turns away the accounts not on it; a change reaches another shard through Redis within 5 s and
 * without Redis within the refresh period; the GM commands need their nodes.
 */
@EnvTest
class ModerationIntegrationTest {

    @TempDir
    Path workingDirectory;

    private TestDatabase database;
    private Database pool;
    private @Nullable EventLedger ledger;
    private final List<RedisLink> links = new ArrayList<>();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        database.execute("INSERT INTO role (role_id) VALUES ('gm');"
                + "INSERT INTO role_permission (role_id, node) VALUES ('gm', 'gm.ban'), ('gm', 'gm.whitelist')");
    }

    @AfterEach
    void drop() {
        for (RedisLink link : links) link.close();
        if (ledger != null) ledger.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** A connection in the login state from a chosen address, keeping what it is sent. */
    private static final class Wire extends PlayerConnection {
        private final List<ServerPacket> sent = new CopyOnWriteArrayList<>();
        private final InetSocketAddress address;

        Wire(ServerProcess process, String ip) {
            super(process);
            this.address = new InetSocketAddress(InetAddress.ofLiteral(ip), 50_000);
            setClientState(ConnectionState.LOGIN);
            setServerState(ConnectionState.LOGIN);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            sent.add(packet instanceof ServerPacket direct ? direct
                    : SendablePacket.extractServerPacket(getServerState(), packet));
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return address;
        }

        @Nullable TranslatableComponent refusal() {
            final List<LoginDisconnectPacket> refusals = sent.stream().filter(LoginDisconnectPacket.class::isInstance)
                    .map(LoginDisconnectPacket.class::cast).toList();
            return refusals.isEmpty() ? null : (TranslatableComponent) refusals.getFirst().kickMessage();
        }

        boolean loggedIn() {
            return sent.stream().anyMatch(LoginSuccessPacket.class::isInstance);
        }
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

    /** A plugin that lets every player into one match, counts the admissions and keeps its context. */
    private static final class Probe implements SynaxisPlugin {
        private final Instance match;
        private final AtomicInteger admissions = new AtomicInteger();
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
            ctx.admission().register(_ -> {
                admissions.incrementAndGet();
                return new Admission.Accept(match, 1);
            });
        }

        @Override
        public void onDisable() {
        }
    }

    private HostModeration shard(Env env, String name, boolean withRedis, Duration refresh, boolean whitelist)
            throws Exception {
        final String shardId = name + "-" + run;
        final RedisLink link = withRedis ? RedisLink.connect(TestDatabase.redisUrl(), shardId) : RedisLink.disabled(shardId);
        links.add(link);
        final HostModeration moderation = new HostModeration(env.process(), new ModerationStore(pool.pool()), link,
                refresh, whitelist, (event, attributes) -> events.add(event + " " + attributes));
        moderation.start();
        final EventNode<Event> node = EventNode.all("moderation-" + shardId);
        env.process().eventHandler().addChild(node);
        moderation.attach(node);
        return moderation;
    }

    private HostPermissions permissions(Env env) {
        final HostPermissions permissions = new HostPermissions(env.process(), new RoleStore(pool.pool()),
                RedisLink.disabled("roles-" + run), HostPermissions.REFRESH);
        final EventNode<Event> node = EventNode.all("permissions-" + run);
        env.process().eventHandler().addChild(node);
        permissions.attach(node);
        return permissions;
    }

    /** Logs a connection in the way the engine does once the Mojang verification is done. */
    private static Wire login(Env env, UUID account, String ip) {
        final Wire wire = new Wire(env.process(), ip);
        final Thread thread = Thread.startVirtualThread(() ->
                env.process().connection().transitionLoginToConfig(wire, new GameProfile(account, "Player")));
        try {
            assertTrue(thread.join(Duration.ofSeconds(10)), "the login never returned");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        return wire;
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

    private static String text(Component component) {
        return ((TextComponent) component).content();
    }

    @Test
    void aBannedAccountIsTurnedAwayAfterVerificationAndBeforeTheAdmission(Env env) throws Exception {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final HostModeration moderation = new HostModeration(env.process(), new ModerationStore(pool.pool()),
                RedisLink.disabled("test"), HostModeration.REFRESH, false, ledger::record);
        moderation.start();
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger,
                PlayerStore.inMemory(env.process(), ledger::record), null, HostPermissions.withoutDatabase(env.process()),
                moderation);
        final Probe probe = new Probe(env.createFlatInstance());
        host.enable(List.of(probe), Set.of());
        final PluginContext context = probe.context;
        final Moderation seen = context == null ? null : context.moderation();
        assertSame(moderation, seen, "a plugin reaches the host's moderation");

        final UUID banned = UUID.randomUUID();
        final UUID forever = UUID.randomUUID();
        final Instant until = Instant.now().plus(1, ChronoUnit.DAYS);
        final Ban ban = moderation.ban(new BanTarget.ByUuid(banned), "griefing", until, HostModeration.CONSOLE).join();
        moderation.ban(new BanTarget.ByUuid(forever), "cheating", null, HostModeration.CONSOLE).join();
        assertEquals(ban.banId(), moderation.activeBan(banned, null).orElseThrow().banId());

        final Wire refused = login(env, banned, "198.51.100.7");
        final TranslatableComponent reason = refused.refusal();
        assertTrue(reason != null && DisconnectKeys.BANNED.equals(reason.key()), "refused with " + reason);
        assertEquals("griefing", text(reason.arguments().get(0).asComponent()));
        final TranslatableComponent expiry = (TranslatableComponent) reason.arguments().get(1).asComponent();
        assertEquals("synaxis:banned-until", expiry.key());
        assertEquals(until.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")),
                text(expiry.arguments().getFirst().asComponent()));
        assertFalse(refused.loggedIn(), "no LoginSuccess after the refusal");
        assertFalse(refused.isOnline());

        final TranslatableComponent permanent = login(env, forever, "198.51.100.8").refusal();
        assertTrue(permanent != null && DisconnectKeys.BANNED.equals(permanent.key()));
        assertEquals(Component.empty(), permanent.arguments().get(1).asComponent(), "a permanent ban names no expiry");

        final Wire admitted = login(env, UUID.randomUUID(), "198.51.100.9");
        assertTrue(admitted.loggedIn() && admitted.refusal() == null, "an account without a ban logs in");
        assertEquals(0, probe.admissions.get(), "the admission provider comes later, in the configuration phase");
        host.disableAll();
    }

    @Test
    void aBanKicksTheOnlineTargetWithinFiveSecondsAndTheCommandNeedsTheNode(Env env) throws Exception {
        final HostModeration moderation = shard(env, "a", false, HostModeration.REFRESH, false);
        final HostPermissions permissions = permissions(env);
        new ModerationCommands(env.process(), moderation, permissions, (event, attributes) ->
                events.add(event + " " + attributes)).commands().forEach(env.process().command()::register);
        final Instance instance = env.createFlatInstance();
        final UUID gm = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        database.execute("INSERT INTO account_role (account_uuid, role_id) VALUES ('" + gm + "', 'gm')");
        final Player g = env.createConnection(new GameProfile(gm, "Gamemaster")).connect(instance, new Pos(0, 41, 0));
        permissions.load(g);
        final Player n = env.createConnection(new GameProfile(UUID.randomUUID(), "Nobody")).connect(instance, new Pos(0, 41, 0));
        permissions.load(n);
        n.setPermissionLevel(4);
        final Player v = env.createConnection(new GameProfile(target, "Villain")).connect(instance, new Pos(0, 41, 0));

        assertEquals(CommandResult.Type.CANCELLED, env.process().command().execute(n, "ban Villain perm cheating").getType(),
                "permission level 4 is no gm.ban");
        assertEquals(CommandResult.Type.CANCELLED, env.process().command().execute(n, "whitelist add " + target).getType());
        assertTrue(v.isOnline());
        final long start = System.nanoTime();
        assertEquals(CommandResult.Type.SUCCESS, env.process().command().execute(g, "ban Villain perm cheating").getType());
        await(env, () -> !v.isOnline(), Duration.ofSeconds(5), "the banned player is kicked");
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis < 5_000, "within 5 s, took " + tookMillis + " ms");
        await(env, () -> events.size() == 2, Duration.ofSeconds(5), "the ban and the command are recorded");

        final Matcher banned = Pattern.compile("player_banned \\{ban_id=\\d+, kind=uuid, operator=" + gm
                + ", was_online=true, kicked_within_ms=(\\d+)\\}").matcher(events.get(0));
        assertTrue(banned.matches(), events.get(0));
        assertTrue(Long.parseLong(banned.group(1)) <= 5_000, events.get(0));
        assertEquals("gm_command {actor=player, actor_uuid=" + gm + ", command=ban Villain perm cheating, target="
                + target + ", result=ok}", events.get(1));
        assertTrue(moderation.activeBan(target, null).isPresent());
        assertTrue(g.isOnline() && n.isOnline(), "nobody else is kicked");
    }

    @Test
    void expiredAndLiftedBansDoNotApplyAndThereIsNoBanByName(Env env) throws Exception {
        final UUID expired = UUID.randomUUID();
        final UUID revoked = UUID.randomUUID();
        database.execute("INSERT INTO ban (kind, subject_uuid, reason, created_by, expires_at) VALUES ('uuid', '"
                + expired + "', 'old', '" + HostModeration.CONSOLE + "', now() - interval '1 second');"
                + "INSERT INTO ban (kind, subject_uuid, reason, created_by, revoked_at) VALUES ('uuid', '"
                + revoked + "', 'lifted', '" + HostModeration.CONSOLE + "', now())");
        final HostModeration moderation = shard(env, "a", false, HostModeration.REFRESH, false);
        assertTrue(login(env, expired, "198.51.100.1").loggedIn(), "an expired ban does not apply");
        assertTrue(login(env, revoked, "198.51.100.2").loggedIn(), "a lifted ban does not apply");

        final UUID pardoned = UUID.randomUUID();
        final Ban ban = moderation.ban(new BanTarget.ByUuid(pardoned), "spam", null, HostModeration.CONSOLE).join();
        assertTrue(login(env, pardoned, "198.51.100.3").refusal() != null);
        assertTrue(moderation.unban(ban.banId(), HostModeration.CONSOLE).join());
        assertFalse(moderation.unban(ban.banId(), HostModeration.CONSOLE).join(), "a lifted ban is not in force");
        assertTrue(login(env, pardoned, "198.51.100.3").loggedIn(), "the lifted ban applies no more");

        final Instant soon = Instant.now().plusMillis(1_500);
        final UUID brief = UUID.randomUUID();
        moderation.ban(new BanTarget.ByUuid(brief), "cool down", soon, HostModeration.CONSOLE).join();
        assertTrue(login(env, brief, "198.51.100.4").refusal() != null);
        await(env, () -> Instant.now().isAfter(soon), Duration.ofSeconds(3), "the ban expires");
        assertTrue(login(env, brief, "198.51.100.4").loggedIn(), "a ban stops applying when it expires, without a read");

        assertEquals(Set.of(BanTarget.ByUuid.class, BanTarget.ByNetwork.class),
                Set.of(BanTarget.class.getPermittedSubclasses()), "an account or a network, never a name");
        assertEquals(Set.of("ban", "unban", "activeBan", "whitelist"),
                Arrays.stream(Moderation.class.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet()));
        assertEquals(BanTarget.class, Moderation.class.getMethod("ban", BanTarget.class, String.class, Instant.class,
                UUID.class).getParameterTypes()[0]);
        assertThrows(IllegalArgumentException.class, () -> moderation.ban(new BanTarget.ByUuid(brief), "", null,
                HostModeration.CONSOLE));
        assertThrows(IllegalArgumentException.class, () -> moderation.ban(new BanTarget.ByUuid(brief), "x".repeat(257),
                null, HostModeration.CONSOLE));
        assertThrows(IllegalArgumentException.class, () -> moderation.ban(new BanTarget.ByUuid(brief), "late",
                Instant.now().minusSeconds(1), HostModeration.CONSOLE));
    }

    @Test
    void aNetworkBanCoversEveryAddressOfTheNetwork(Env env) throws Exception {
        final HostModeration moderation = shard(env, "a", false, HostModeration.REFRESH, false);
        moderation.ban(new BanTarget.ByNetwork("10.20.30.40/16"), "attack", null, HostModeration.CONSOLE).join();
        moderation.ban(new BanTarget.ByNetwork("2001:db8::/32"), "attack", null, HostModeration.CONSOLE).join();

        final TranslatableComponent inside = login(env, UUID.randomUUID(), "10.20.200.1").refusal();
        assertTrue(inside != null && DisconnectKeys.BANNED.equals(inside.key()), "any account of the network");
        assertTrue(login(env, UUID.randomUUID(), "2001:db8:1::1").refusal() != null);
        assertTrue(login(env, UUID.randomUUID(), "10.21.0.1").loggedIn(), "the next network is not covered");
        assertTrue(login(env, UUID.randomUUID(), "2001:db9::1").loggedIn());
        assertTrue(moderation.activeBan(UUID.randomUUID(), null).isEmpty(), "without an address no network covers");
    }

    @Test
    void theWhitelistTurnsAwayAnAccountNotOnIt(Env env) throws Exception {
        final HostModeration closed = shard(env, "closed", false, HostModeration.REFRESH, true);
        new ModerationCommands(env.process(), closed, HostPermissions.withoutDatabase(env.process()), (_, _) -> { })
                .commands().forEach(env.process().command()::register);
        final RecordingConsole console = new RecordingConsole(env.process());
        final UUID invited = UUID.randomUUID();

        final TranslatableComponent refused = login(env, invited, "198.51.100.1").refusal();
        assertTrue(refused != null && DisconnectKeys.NOT_WHITELISTED.equals(refused.key()), "refused with " + refused);
        env.process().command().execute(console, "whitelist add " + invited);
        await(env, () -> console.answers.size() == 1, Duration.ofSeconds(5), "the answer");
        assertTrue(login(env, invited, "198.51.100.1").loggedIn(), "on the list, let in");
        env.process().command().execute(console, "whitelist remove " + invited);
        await(env, () -> console.answers.size() == 2, Duration.ofSeconds(5), "the answer");
        assertTrue(login(env, invited, "198.51.100.1").refusal() != null, "off the list again");
        assertEquals(List.of("synaxis:whitelist-added", "synaxis:whitelist-removed"), console.answers);
    }

    @Test
    void theWhitelistIsNotEnforcedWhenItIsOff(Env env) throws Exception {
        shard(env, "open", false, HostModeration.REFRESH, false);
        assertTrue(login(env, UUID.randomUUID(), "198.51.100.1").loggedIn());
    }

    @Test
    void aChangeReachesAnotherShardThroughRedisWithinFiveSeconds(Env env) throws Exception {
        final HostModeration first = shard(env, "a", true, HostModeration.REFRESH, false);
        final HostModeration second = shard(env, "b", true, HostModeration.REFRESH, false);
        final UUID account = UUID.randomUUID();
        // Time for the listener threads of both shards to subscribe
        TimeUnit.MILLISECONDS.sleep(500);

        long start = System.nanoTime();
        final Ban ban = first.ban(new BanTarget.ByUuid(account), "cheating", null, HostModeration.CONSOLE).join();
        await(env, () -> second.activeBan(account, null).isPresent(), Duration.ofSeconds(5), "the other shard knows the ban");
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis < 5_000, "within 5 s, took " + tookMillis + " ms");

        start = System.nanoTime();
        first.unban(ban.banId(), HostModeration.CONSOLE).join();
        await(env, () -> second.activeBan(account, null).isEmpty(), Duration.ofSeconds(5), "the other shard drops it");
        tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis < 5_000, "within 5 s, took " + tookMillis + " ms");
    }

    /**
     * The pool of the store, one connection at a time: the next one passes, is refused, or holds its
     * {@code close()} (the statements have run, the rows are read) until released.
     */
    private static final class ScriptedPool implements InvocationHandler {
        enum Step { PASS, REFUSE, HOLD }

        private final DataSource pool;
        private final Queue<Step> steps = new ConcurrentLinkedQueue<>();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        ScriptedPool(DataSource pool) {
            this.pool = pool;
        }

        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (!method.getName().equals("getConnection") || arguments != null) return forward(pool, method, arguments);
            final Step step = Objects.requireNonNullElse(steps.poll(), Step.PASS);
            if (step == Step.REFUSE) throw new SQLException("refused by the test");
            final Connection connection = pool.getConnection();
            if (step == Step.PASS) return connection;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (_, called, passed) -> {
                        if (called.getName().equals("close")) {
                            held.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS), "never released");
                        }
                        return forward(connection, called, passed);
                    });
        }

        private static Object forward(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * {@code API-PROD-009}: a change made on this shard whose read afterwards fails is put in by hand,
     * and a read that started before the change and returns after it does not take it back. Once with a
     * ban and once with an unban; the read that started first is {@link HostModeration#start()} on a
     * thread of its own, held after its statements ran.
     */
    @Test
    void aChangePutInByHandOutlivesAReadThatStartedBeforeIt(Env env) throws Exception {
        final ScriptedPool scripted = new ScriptedPool(pool.pool());
        final HostModeration moderation = new HostModeration(env.process(), new ModerationStore(scripted.dataSource()),
                RedisLink.disabled("by-hand"), HostModeration.REFRESH, false, (_, _) -> { });
        final UUID account = UUID.randomUUID();

        scripted.steps.addAll(List.of(ScriptedPool.Step.HOLD, ScriptedPool.Step.PASS, ScriptedPool.Step.REFUSE));
        final Thread earlier = Thread.ofVirtual().start(() -> assertDoesNotThrow(moderation::start));
        assertTrue(scripted.held.await(10, TimeUnit.SECONDS), "the earlier read ran its statements");
        final Ban ban = moderation.ban(new BanTarget.ByUuid(account), "griefing", null, HostModeration.CONSOLE).join();
        assertTrue(moderation.activeBan(account, null).isPresent(), "put in by hand");
        scripted.release.countDown();
        assertTrue(earlier.join(Duration.ofSeconds(10)), "the earlier read returned");
        assertTrue(moderation.activeBan(account, null).isPresent(),
                "the earlier read, which saw no ban, did not take the one put in by hand back");

        final ScriptedPool lifting = new ScriptedPool(pool.pool());
        final HostModeration second = new HostModeration(env.process(), new ModerationStore(lifting.dataSource()),
                RedisLink.disabled("by-hand-lift"), HostModeration.REFRESH, false, (_, _) -> { });
        second.start();
        assertTrue(second.activeBan(account, null).isPresent());
        lifting.steps.addAll(List.of(ScriptedPool.Step.HOLD, ScriptedPool.Step.PASS, ScriptedPool.Step.REFUSE));
        final Thread before = Thread.ofVirtual().start(() -> assertDoesNotThrow(second::start));
        assertTrue(lifting.held.await(10, TimeUnit.SECONDS), "the earlier read ran its statements");
        assertTrue(second.unban(ban.banId(), HostModeration.CONSOLE).join());
        assertTrue(second.activeBan(account, null).isEmpty(), "lifted by hand");
        lifting.release.countDown();
        assertTrue(before.join(Duration.ofSeconds(10)), "the earlier read returned");
        assertTrue(second.activeBan(account, null).isEmpty(),
                "the earlier read, which saw the ban, did not bring it back");
    }

    @Test
    void withoutRedisTheRefreshCatchesUpWithinItsPeriodAndKicks(Env env) throws Exception {
        assertEquals(Duration.ofSeconds(30), HostModeration.REFRESH, "§16: 30 s while Redis is unreachable");
        final HostModeration moderation = shard(env, "b", false, Duration.ofSeconds(1), false);
        final UUID account = UUID.randomUUID();
        final Player player = env.createConnection(new GameProfile(account, "Roamer"))
                .connect(env.createFlatInstance(), new Pos(0, 41, 0));

        // Written by another shard that could not tell this one
        database.execute("INSERT INTO ban (kind, subject_uuid, reason, created_by) VALUES ('uuid', '" + account
                + "', 'elsewhere', '" + HostModeration.CONSOLE + "')");
        await(env, () -> !player.isOnline(), Duration.ofSeconds(3), "the refresh kicks the banned player");
        assertTrue(moderation.activeBan(account, null).isPresent());

        database.execute("UPDATE ban SET revoked_at = now() WHERE subject_uuid = '" + account + "'");
        await(env, () -> moderation.activeBan(account, null).isEmpty(), Duration.ofSeconds(3), "the refresh lifts it");
    }

    @Test
    void theConsoleBansLiftsAndListsAndEveryRunIsOneEvent(Env env) throws Exception {
        final HostModeration moderation = shard(env, "a", false, HostModeration.REFRESH, false);
        new ModerationCommands(env.process(), moderation, HostPermissions.withoutDatabase(env.process()),
                (event, attributes) -> {
                    if (event.equals("gm_command")) events.add(attributes.get("result") + " " + attributes.get("target"));
                }).commands().forEach(env.process().command()::register);
        final RecordingConsole console = new RecordingConsole(env.process());
        final UUID account = UUID.randomUUID();

        final List<String> inputs = List.of("ban Stranger perm spam", "ban " + account + " 5x spam",
                "ban 10.0.0.0/7 perm spam", "ban " + account + " 10m " + "x".repeat(257),
                "ban " + account + " 10m spam", "unban nothing", "unban " + Long.MAX_VALUE, "whitelist add " + account,
                "whitelist remove Stranger");
        for (int i = 0; i < inputs.size(); i++) {
            env.process().command().execute(console, inputs.get(i));
            final int expected = i + 1;
            await(env, () -> console.answers.size() == expected, Duration.ofSeconds(5), inputs.get(i));
        }
        assertEquals(List.of("synaxis:moderation-no-target", "synaxis:moderation-bad-duration",
                "synaxis:moderation-no-target", "synaxis:moderation-bad-reason", "synaxis:ban-done",
                "synaxis:unban-none", "synaxis:unban-none", "synaxis:whitelist-added", "synaxis:moderation-no-target"),
                console.answers);
        await(env, () -> events.size() == inputs.size() + 1, Duration.ofSeconds(5), "every run is recorded");
        final List<String> commands = events.stream().filter(line -> !line.startsWith("player_banned")).toList();
        assertEquals(List.of("no_target null", "bad_duration " + account, "no_target null", "bad_reason " + account,
                "ok " + account, "no_ban null", "no_ban " + Long.MAX_VALUE, "ok " + account, "no_target null"), commands);
        final Ban ban = moderation.activeBan(account, null).orElseThrow();
        final Instant expires = ban.expiresAt();
        assertTrue(expires != null && expires.isAfter(Instant.now().plus(Duration.ofMinutes(9)))
                && expires.isBefore(Instant.now().plus(Duration.ofMinutes(11))), "10m is ten minutes: " + expires);

        env.process().command().execute(console, "unban " + ban.banId());
        await(env, () -> console.answers.size() == inputs.size() + 1, Duration.ofSeconds(5), "the lift");
        assertEquals("synaxis:unban-done", console.answers.getLast());
        assertTrue(moderation.activeBan(account, null).isEmpty(), "this shard reads its own change at once");
    }

    @Test
    void withoutADatabaseNobodyIsBannedAndNothingChanges(Env env) {
        final HostModeration moderation = HostModeration.withoutDatabase(env.process());
        moderation.attach(EventNode.all("unused"));
        final CompletionException failed = assertThrows(CompletionException.class, () -> moderation.ban(
                new BanTarget.ByUuid(UUID.randomUUID()), "spam", null, HostModeration.CONSOLE).join());
        assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertTrue(moderation.activeBan(UUID.randomUUID(), InetAddress.getLoopbackAddress()).isEmpty());

        final RecordingConsole console = new RecordingConsole(env.process());
        final List<Map<String, ?>> recorded = new CopyOnWriteArrayList<>();
        new ModerationCommands(env.process(), moderation, HostPermissions.withoutDatabase(env.process()),
                (_, attributes) -> recorded.add(attributes)).commands().forEach(env.process().command()::register);
        env.process().command().execute(console, "ban " + UUID.randomUUID() + " perm spam");
        await(env, () -> !console.answers.isEmpty(), Duration.ofSeconds(5), "the answer");
        assertEquals(List.of("synaxis:moderation-unavailable"), console.answers);
        // The command answers first and records its gm_command line after it returns
        await(env, () -> !recorded.isEmpty(), Duration.ofSeconds(5), "the ledger line");
        assertEquals("unavailable", recorded.getFirst().get("result"));

        assertThrows(IllegalArgumentException.class, () -> new HostModeration(env.process(), null,
                RedisLink.disabled("memory"), HostModeration.REFRESH, true, (_, _) -> { }),
                "the whitelist lives in the database");
    }

    @Test
    void theWhitelistSwitchIsOnOrOff() {
        assertFalse(Main.whitelist(), "off when not set");
        try {
            System.setProperty("synaxis.app.whitelist", "on");
            assertTrue(Main.whitelist());
            System.setProperty("synaxis.app.whitelist", "yes");
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, Main::whitelist);
            assertTrue(refused.getMessage().contains("synaxis.app.whitelist=yes is not on or off"), refused.getMessage());
        } finally {
            System.clearProperty("synaxis.app.whitelist");
        }
    }

    /**
     * A network ban written into the table by hand that no ban can hold, an IPv4 mapped IPv6 network, is read as a
     * failed read: a starting shard refuses naming the ban, and a running one keeps what it had, puts a ban made
     * meanwhile in by hand and still answers it.
     */
    @Test
    void aBanRowNoBanCanHoldIsAFailedReadThatNamesIt(Env env) throws Exception {
        final HostModeration running = shard(env, "a", false, HostModeration.REFRESH, false);
        database.execute("INSERT INTO ban (kind, subject_ip, reason, created_by) VALUES ('network', "
                + "'::ffff:10.0.0.0/104', 'mapped', '" + HostModeration.CONSOLE + "')");
        final long banId;
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT ban_id FROM ban WHERE reason = 'mapped'")) {
            assertTrue(row.next());
            banId = row.getLong(1);
        }

        final HostModeration starting = new HostModeration(env.process(), new ModerationStore(pool.pool()),
                RedisLink.disabled("b-" + run), HostModeration.REFRESH, false, (_, _) -> { });
        final SQLException refused = assertThrows(SQLException.class, starting::start);
        assertTrue(refused.getMessage().contains("ban " + banId + " "), refused.getMessage());

        final UUID account = UUID.randomUUID();
        final Ban ban = running.ban(new BanTarget.ByUuid(account), "spam", null, HostModeration.CONSOLE)
                .get(10, TimeUnit.SECONDS);
        assertEquals(Optional.of(ban), running.activeBan(account, null), "put in by hand when the read again failed");
    }

    @Test
    void theDatabaseRefusesAMalformedBan() {
        final String console = "'" + HostModeration.CONSOLE + "'";
        for (String values : List.of(
                "('network', NULL, '10.0.0.0/7', 'too wide', " + console + ")",
                "('network', NULL, '2001:db8::/31', 'too wide', " + console + ")",
                "('uuid', NULL, NULL, 'no subject', " + console + ")",
                "('uuid', '" + UUID.randomUUID() + "', '10.0.0.0/8', 'both', " + console + ")",
                "('uuid', '" + UUID.randomUUID() + "', NULL, '', " + console + ")",
                "('uuid', '" + UUID.randomUUID() + "', NULL, '" + "x".repeat(257) + "', " + console + ")",
                "('name', NULL, NULL, 'by name', " + console + ")")) {
            final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> database.execute(
                    "INSERT INTO ban (kind, subject_uuid, subject_ip, reason, created_by) VALUES " + values), values);
            assertTrue(refused.getCause().getMessage().contains("ban_") && refused.getCause().getMessage().contains("check"),
                    refused.getCause().getMessage());
        }
        database.execute("INSERT INTO ban (kind, subject_ip, reason, created_by) VALUES ('network', '10.0.0.0/8', 'ok', "
                + console + "), ('network', '2001:db8::/32', 'ok', " + console + ")");
    }
}
