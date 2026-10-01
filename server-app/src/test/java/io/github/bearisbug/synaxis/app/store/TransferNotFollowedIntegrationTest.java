package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.app.transfer.TicketIssuer;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.OutgoingTransferEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

import java.lang.reflect.Method;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client told to transfer that stays on the source shard ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-028} issuing ④, §11.3 {@code in_transit}, §28.2): the source disconnects it once
 * {@link #GRACE} is over, and the ticket it holds still gets it in on the target; a transfer whose
 * {@link OutgoingTransferEvent} a plugin cancelled is let go the same way. Until then the presence
 * refresh reports the player in transit, not online, and {@link PlayerStore#isOnline}, which a caller
 * asks before it does work for a transfer, is false for it. The recording connection never leaves on
 * its own and the test answers its keep-alives, which is what a modified client looks like from here.
 */
@EnvTest
class TransferNotFollowedIntegrationTest {

    private static final String SOURCE = "r2-c1";
    private static final String TARGET = "r1-c1";
    private static final TransferKeys KEYS = TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
    /** How long a client told to transfer has to leave. */
    private static final Duration GRACE = Duration.ofSeconds(10);

    private TestDatabase database;
    private Database pool;
    private final List<StoreFixture.Shard> shards = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.ONLINE);
    }

    @AfterEach
    void drop() {
        for (StoreFixture.Shard shard : shards) shard.store().close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private StoreFixture.Shard shard(Env env, String shardId, RedisLink redis) throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, true, redis, StoreFixture.FAST);
        shards.add(shard);
        return shard;
    }

    private static Player online(Env env, StoreFixture.Shard shard, UUID account, String name) {
        final Player player = connect(env.process(), account, name);
        assertTrue(shard.store().load(player), "loaded");
        shard.store().admitted(player);
        spawn(env.process(), player, env.createFlatInstance());
        return player;
    }

    private TicketIssuer issuer(StoreFixture.Shard source) {
        return new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, clock::get), SOURCE,
                (event, attributes) -> source.ledger().add(event + " " + attributes));
    }

    private static TransferOutcome transferOut(Env env, TicketIssuer issuer, Player player) {
        final CompletableFuture<TransferOutcome> outcome = issuer.transfer(player, TARGET, "127.0.0.1", 25631);
        await(env, outcome::isDone, "the transfer completes");
        return outcome.join();
    }

    private static List<SendablePacket> sent(Player player) {
        return ((StoreFixture.RecordingConnection) player.getPlayerConnection()).sent;
    }

    /**
     * Ticks for as long as asked, so that the player and its scheduler run meanwhile; the client
     * answers every keep-alive, as one that stays on purpose does.
     */
    private static void tickUntil(Env env, Player player, long deadlineNanos) {
        while (System.nanoTime() - deadlineNanos < 0) {
            player.refreshAnswerKeepAlive(true);
            env.tick();
            sleep();
        }
    }

    /** The same until the condition holds, failing at the deadline. */
    private static void tickUntil(Env env, Player player, long deadlineNanos, BooleanSupplier condition, String what) {
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadlineNanos >= 0) throw new AssertionError("not by the deadline: " + what);
            player.refreshAnswerKeepAlive(true);
            env.tick();
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aClientThatIgnoresItsTransferIsDisconnected(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE, RedisLink.disabled(SOURCE));
        final StoreFixture.Shard target = shard(env, TARGET, RedisLink.disabled(TARGET));
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, account, "Lingerer");

        assertEquals(TransferOutcome.SENT, transferOut(env, issuer(source), player));
        final long sentAt = System.nanoTime();
        final List<SendablePacket> sent = sent(player);
        assertTrue(sent.stream().anyMatch(TransferPacket.class::isInstance), "told where to go");
        final byte[] ticket = assertInstanceOf(CookieStorePacket.class,
                sent.stream().filter(CookieStorePacket.class::isInstance).findFirst().orElseThrow()).value();

        tickUntil(env, player, sentAt + GRACE.minusSeconds(1).toNanos());
        assertTrue(player.isOnline(), "the client gets its grace");
        assertTrue(source.store().isFrozen(player), "in transit meanwhile");

        tickUntil(env, player, sentAt + GRACE.plusSeconds(5).toNanos(), () -> !player.isOnline(),
                "the source lets go of a client that stays");
        assertEquals(List.of("synaxis:transfer-rejected"), kicks(player));
        await(env, () -> source.store().loadedCount() == 0, "the source forgets the player");
        assertNull(row(database, account).lease(), "nothing taken back on the way out");

        // The ticket still gets the player in, within its lifetime
        final StoreFixture.RecordingConnection connection = new StoreFixture.RecordingConnection(env.process());
        connection.setServerState(ConnectionState.CONFIGURATION);
        connection.markTransferred(true);
        connection.cookie = ticket;
        final Player arrived = new Player(connection, new GameProfile(account, "Lingerer"));
        final TicketVerifier verifier = new TicketVerifier(pool.pool(), target::sessionId, TARGET, KEYS,
                new TransferCooldowns(5_000, clock::get), (event, attributes) -> target.ledger().add(event + " " + attributes),
                (_, _) -> {});
        final TransferTicket read = verifier.read(arrived);
        assertTrue(read != null && target.store().load(arrived, () -> verifier.claim(arrived, read)), "the ticket is good");
        assertEquals(target.sessionId(), row(database, account).lease());
    }

    @Test
    void aTransferWhoseEventAPluginCancelledLetsThePlayerGoAllTheSame(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE, RedisLink.disabled(SOURCE));
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, account, "Held");
        env.process().eventHandler().addListener(OutgoingTransferEvent.class, event -> event.setCancelled(true));

        assertEquals(TransferOutcome.SENT, transferOut(env, issuer(source), player));
        final long sentAt = System.nanoTime();
        assertTrue(sent(player).stream().noneMatch(TransferPacket.class::isInstance), "the plugin kept the transfer back");

        tickUntil(env, player, sentAt + GRACE.minusSeconds(1).toNanos());
        assertTrue(player.isOnline(), "the same grace");
        assertTrue(source.store().isFrozen(player), "in transit meanwhile");

        tickUntil(env, player, sentAt + GRACE.plusSeconds(5).toNanos(), () -> !player.isOnline(),
                "the source lets go of a player nobody told to leave");
        assertEquals(List.of("synaxis:transfer-rejected"), kicks(player));
        await(env, () -> source.store().loadedCount() == 0, "the source forgets the player");
        assertNull(row(database, account).lease(), "the character is free for a login anywhere");
    }

    @Test
    void thePresenceRefreshReportsAPlayerInTransitAsInTransit(Env env) throws Exception {
        final String run = UUID.randomUUID().toString().substring(0, 8);
        final StoreFixture.Shard source = shard(env, SOURCE, RedisLink.connect(TestDatabase.redisUrl(), SOURCE + "-" + run));
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, account, "Wayfarer");
        try (RedisClient redis = RedisClient.create(URI.create(TestDatabase.redisUrl()))) {
            assertTrue(redis.get("presence:" + account).contains("\"state\":\"online\""), "online once admitted");

            assertEquals(TransferOutcome.SENT, transferOut(env, issuer(source), player));
            assertTrue(redis.get("presence:" + account).contains("\"state\":\"in_transit\""), "in transit once released");

            // The refresh runs every 10 s on its own; called here so that it surely runs before the grace is over
            final Method refresh = PlayerStore.class.getDeclaredMethod("refreshPresence");
            refresh.setAccessible(true);
            refresh.invoke(source.store());
            final String presence = redis.get("presence:" + account);
            assertTrue(presence.contains("\"state\":\"in_transit\""), "the refresh keeps it in transit: " + presence);
            redis.del("presence:" + account);
        }
    }

    @Test
    void onlyAPlayerATransferWouldTakeIsOnlineToTheStore(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE, RedisLink.disabled(SOURCE));
        final Player stranger = connect(env.process(), UUID.randomUUID(), "Stranger");
        final Player player = online(env, source, UUID.randomUUID(), "Gatewalker");
        assertFalse(source.store().isOnline(stranger), "not loaded here");
        assertTrue(source.store().isOnline(player));

        assertEquals(TransferOutcome.SENT, transferOut(env, issuer(source), player));
        assertFalse(source.store().isOnline(player), "in transit");
    }
}
