package io.github.bearisbug.synaxis.app.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.app.transfer.TicketIssuer;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
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
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.count;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transfer tickets across two shards of one database ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-045}, {@code API-PROD-028}, §11.3 {@code saving → in_transit → loading}): the
 * issuing side, every check of the target side in its order, the one time nonce, the version
 * assertion and the cooldown a ticket carries across. Source and target live in one process, which
 * is all §26 asks of the 3D acceptance: no {@code ShardService} is needed.
 */
@EnvTest
class TransferTicketsIntegrationTest {

    private static final String SOURCE = "r2-c1";
    private static final String TARGET = "r1-c1";
    private static final TransferKeys KEYS = TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));

    private TestDatabase database;
    private Database pool;
    private final List<StoreFixture.Shard> shards = new ArrayList<>();
    private final AtomicLong sourceClock = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong targetClock = new AtomicLong(System.currentTimeMillis());
    private final List<Claimed> claimed = new CopyOnWriteArrayList<>();

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

    private StoreFixture.Shard shard(Env env, String shardId) throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, true, RedisLink.disabled(shardId),
                StoreFixture.FAST);
        shards.add(shard);
        return shard;
    }

    private static Player online(Env env, StoreFixture.Shard shard, Instance instance, UUID account, String name) {
        final Player player = connect(env.process(), account, name);
        assertTrue(shard.store().load(player), "loaded");
        shard.store().admitted(player);
        spawn(env.process(), player, instance);
        return player;
    }

    /** A connection arriving at the target with the transfer intent and the given cookie. */
    private static Player arriving(Env env, UUID account, byte[] cookie) {
        final StoreFixture.RecordingConnection connection = new StoreFixture.RecordingConnection(env.process());
        connection.setServerState(ConnectionState.CONFIGURATION);
        connection.markTransferred(true);
        connection.cookie = cookie;
        return new Player(connection, new GameProfile(account, "Traveller"));
    }

    private TicketVerifier verifier(StoreFixture.Shard target) {
        return new TicketVerifier(pool.pool(), target::sessionId, TARGET, KEYS,
                new TransferCooldowns(5_000, targetClock::get), (event, attributes) -> target.ledger().add(event + " " + attributes),
                (player, ticket) -> claimed.add(new Claimed(player, ticket)));
    }

    /** What the verifier told its {@code onClaimed} ({@code docs/DESIGN-M3.md} {@code API-M3-010}). */
    private record Claimed(Player player, TransferTicket ticket) {
    }

    /** The {@code EVT:transfer_issued} line of a ticket, as the issuer's ledger records it. */
    private static String issued(TransferTicket ticket) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("transfer_ref", ticket.reference());
        attributes.put("player_uuid", ticket.playerUuid());
        attributes.put("source_shard", ticket.sourceShard());
        attributes.put("target_shard", ticket.targetShard());
        attributes.put("issued_at", ticket.issuedAt());
        return "transfer_issued " + attributes;
    }

    /** What the admission gate does with a transfer: the ticket read first, then claimed in the load. */
    private static boolean arrive(StoreFixture.Shard target, TicketVerifier verifier, Player player) {
        final TransferTicket ticket = verifier.read(player);
        return ticket != null && target.store().load(player, () -> verifier.claim(player, ticket));
    }

    /** The target's store, whose first load holds on to the lease it acquired until the test lets it go. */
    private static final class FirstLoadHeld implements StoreBackend {
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        private final AtomicInteger loads = new AtomicInteger();
        private final PostgresBackend postgres;

        FirstLoadHeld(Database pool) {
            this.postgres = new PostgresBackend(pool.pool());
        }

        @Override
        public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
            final LoadOutcome outcome = postgres.load(account, name, sessionId);
            if (loads.getAndIncrement() == 0) {
                holding.countDown();
                try {
                    letGo.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            return outcome;
        }

        @Override
        public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
            postgres.write(sessionId, writes);
        }

        @Override
        public void release(long characterId, long sessionId) throws SQLException {
            postgres.release(characterId, sessionId);
        }

        @Override
        public void releaseAccount(UUID account, long sessionId) throws SQLException {
            postgres.releaseAccount(account, sessionId);
        }
    }

    private StoreFixture.Shard heldTarget(Env env, FirstLoadHeld backend, PlayerStore.Options options) throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, TARGET, true, RedisLink.disabled(TARGET), options,
                StoreFixture.MANUAL, backend);
        shards.add(shard);
        return shard;
    }

    /** Transfers the player out of the source and returns the ticket the client was given. */
    private static byte[] transferOut(Env env, TicketIssuer issuer, Player player) {
        final CompletableFuture<TransferOutcome> outcome = issuer.transfer(player, TARGET, "127.0.0.1", 25631);
        await(env, outcome::isDone, "the transfer completes");
        assertEquals(TransferOutcome.SENT, outcome.join());
        final List<SendablePacket> sent = ((StoreFixture.RecordingConnection) player.getPlayerConnection()).sent;
        final int cookieAt = indexOf(sent, CookieStorePacket.class);
        final int transferAt = indexOf(sent, TransferPacket.class);
        assertTrue(cookieAt >= 0 && cookieAt < transferAt, "the cookie is stored before the transfer: " + sent);
        final CookieStorePacket cookie = assertInstanceOf(CookieStorePacket.class, sent.get(cookieAt));
        assertEquals(TransferTicket.COOKIE, cookie.key());
        return cookie.value();
    }

    private static int indexOf(List<SendablePacket> sent, Class<?> type) {
        for (int i = 0; i < sent.size(); i++) if (type.isInstance(sent.get(i))) return i;
        return -1;
    }

    private static List<String> reasons(StoreFixture.Shard shard) {
        return shard.events("transfer_rejected").stream()
                .map(line -> line.substring(line.indexOf("reason=") + 7, line.indexOf(',', line.indexOf("reason="))))
                .toList();
    }

    @Test
    void badTicketsAreRefusedInCheckOrderAndAGoodOneOnlyOnce(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final StoreFixture.Shard target = shard(env, TARGET);
        final TicketVerifier verifier = verifier(target);
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Traveller");
        final List<SendablePacket> sent = ((StoreFixture.RecordingConnection) player.getPlayerConnection()).sent;
        final List<Boolean> transferSentFirst = new CopyOnWriteArrayList<>();
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE,
                (event, attributes) -> {
                    transferSentFirst.add(indexOf(sent, TransferPacket.class) >= 0);
                    source.ledger().add(event + " " + attributes);
                });
        source.store().get(player, PURSE).coins = 11;
        source.store().markDirty(player, PURSE);

        final byte[] good = transferOut(env, issuer, player);
        final TransferTicket ticket = TransferTicket.decode(good, KEYS);
        assertEquals(account, ticket.playerUuid());
        assertEquals(row(database, account).version(), ticket.stateVersion(), "the version the release wrote");
        assertNull(row(database, account).lease(), "released before the ticket was sent");
        assertEquals(List.of(issued(ticket)), source.events("transfer_issued"),
                "one event for the ticket sent, with the issued_at of the cookie");
        assertEquals(List.of(true), transferSentFirst, "written once the client was told where to go");

        final byte[] tampered = good.clone();
        tampered[20] ^= 0x01;
        final long now = targetClock.get();
        final byte[] expired = new TransferTicket(7, account, ticket.characterId(), SOURCE, TARGET, ticket.stateVersion(),
                new byte[16], now - 60_000, now - 1_000).encode(KEYS);
        final byte[] elsewhere = new TransferTicket(7, account, ticket.characterId(), SOURCE, SOURCE, ticket.stateVersion(),
                nonce(1), now, now + 30_000).encode(KEYS);
        final byte[] someoneElses = new TransferTicket(7, UUID.randomUUID(), ticket.characterId(), SOURCE, TARGET,
                ticket.stateVersion(), nonce(2), now, now + 30_000).encode(KEYS);
        for (byte[] bad : List.of(tampered, expired, elsewhere, someoneElses)) {
            final Player refused = arriving(env, account, bad);
            assertFalse(arrive(target, verifier, refused));
            assertEquals(List.of("synaxis:transfer-rejected"), kicks(refused));
        }
        assertEquals(List.of("bad-signature", "expired", "wrong-target", "uuid-mismatch"), reasons(target));
        assertEquals(List.of(), claimed, "a refused ticket is no arrival");

        final Player arrived = arriving(env, account, good);
        assertTrue(arrive(target, verifier, arrived), "the good ticket passes and the character loads like any login");
        assertEquals(target.sessionId(), row(database, account).lease(), "the target holds the character now");
        assertEquals(11, target.store().get(arrived, PURSE).coins);
        assertEquals(ticket.stateVersion(), target.store().versionOf(arrived));
        assertEquals(List.of(new Claimed(arrived, ticket)), claimed, "the accepted ticket is told once, with its player");

        // The first arrival goes away before the same ticket comes again
        target.store().abandon(arrived);
        final Player replayed = arriving(env, account, good);
        assertFalse(arrive(target, verifier, replayed));
        assertEquals("nonce-reused", reasons(target).getLast());
        assertEquals(List.of("synaxis:transfer-rejected"), kicks(replayed));
        assertEquals(1, count(database, "SELECT count(*) FROM transfer_nonce"), "only the good ticket used its nonce");
        assertEquals(1, claimed.size(), "the replayed ticket is no arrival");
    }

    @Test
    void aTicketWhoseVersionTheCharacterIsNotAtIsRefusedAndTheLeaseGivenBack(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final StoreFixture.Shard target = shard(env, TARGET);
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE, (_, _) -> {});
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Drifter");
        final byte[] good = transferOut(env, issuer, player);
        database.execute("UPDATE character SET version = version + 1 WHERE account_uuid = '" + account + "'");

        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(TicketVerifier.class)).addAppender(log);
        final Player arrived = arriving(env, account, good);
        try {
            assertFalse(arrive(target, verifier(target), arrived));
        } finally {
            ((Logger) LoggerFactory.getLogger(TicketVerifier.class)).detachAppender(log);
        }

        assertEquals(List.of("version-mismatch"), reasons(target));
        assertEquals(List.of("synaxis:transfer-rejected"), kicks(arrived));
        assertEquals(1, log.list.stream().filter(event -> event.getLevel() == Level.ERROR).count(), "one consistency ERROR");
        assertNull(row(database, account).lease(), "the lease taken for the check goes back");
        assertEquals(List.of(), claimed, "a refused ticket is no arrival");
    }

    @Test
    void theCooldownOfATransferInCarriesToTheTargetShard(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final StoreFixture.Shard target = shard(env, TARGET);
        final long t = sourceClock.get();
        targetClock.set(t);
        final TicketIssuer outOfSource = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE, (_, _) -> {});
        final TransferCooldowns targetCooldowns = new TransferCooldowns(5_000, targetClock::get);
        final TicketVerifier verifier = new TicketVerifier(pool.pool(), target::sessionId, TARGET, KEYS, targetCooldowns,
                (event, attributes) -> target.ledger().add(event + " " + attributes), (_, _) -> {});
        final TicketIssuer outOfTarget = new TicketIssuer(target.store(), KEYS, targetCooldowns, TARGET,
                (event, attributes) -> target.ledger().add(event + " " + attributes));
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Bouncer");
        final byte[] ticket = transferOut(env, outOfSource, player);
        // The client follows the transfer packet and leaves the source; nothing more is written there
        player.getPlayerConnection().disconnect();
        await(env, player::isRemoved, "the transferred player leaves the source");
        assertEquals(row(database, account).version(), TransferTicket.decode(ticket, KEYS).stateVersion());

        // Arrives on the target, as a connection the target's process tracks this time
        final Player arrived = connect(env.process(), account, "Bouncer2");
        final StoreFixture.RecordingConnection connection = (StoreFixture.RecordingConnection) arrived.getPlayerConnection();
        connection.markTransferred(true);
        connection.cookie = ticket;
        assertTrue(arrive(target, verifier, arrived));
        target.store().admitted(arrived);
        spawn(env.process(), arrived, env.createFlatInstance());
        final long versionBefore = row(database, account).version();

        targetClock.set(t + 3_000);
        assertEquals(TransferOutcome.COOLDOWN, outOfTarget.transfer(arrived, SOURCE, "127.0.0.1", 25631).join());
        assertEquals(versionBefore, row(database, account).version(), "a refused transfer writes nothing");
        assertEquals(target.sessionId(), row(database, account).lease());
        assertEquals(List.of(), target.events("transfer_issued"), "and issues nothing");

        targetClock.set(t + 6_000);
        final CompletableFuture<TransferOutcome> later = outOfTarget.transfer(arrived, SOURCE, "127.0.0.1", 25631);
        await(env, later::isDone, "the second transfer completes");
        assertEquals(TransferOutcome.SENT, later.join());
        assertEquals(1, target.events("transfer_issued").size());
    }

    @Test
    void aTransferThatCannotWriteLeavesThePlayerWhereItIs(Env env) throws Exception {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE,
                (event, attributes) -> source.ledger().add(event + " " + attributes));
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Stuck");
        final Player stranger = connect(env.process(), UUID.randomUUID(), "Stranger");
        assertEquals(TransferOutcome.NOT_ONLINE, issuer.transfer(stranger, TARGET, "127.0.0.1", 25631).join());

        final long characterId = row(database, account).characterId();
        try (Connection blocker = database.connect()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("SELECT 1 FROM character WHERE character_id = " + characterId + " FOR UPDATE");
            }
            final CompletableFuture<TransferOutcome> outcome = issuer.transfer(player, TARGET, "127.0.0.1", 25631);
            await(env, outcome::isDone, "the transfer gives up after its one attempt");
            assertEquals(TransferOutcome.SAVE_FAILED, outcome.join());
            blocker.rollback();
        }

        assertEquals(source.sessionId(), row(database, account).lease(), "the player still holds its character here");
        assertFalse(source.store().isFrozen(player), "and is back to online");
        assertEquals(List.of("synaxis:transfer-failed"), StoreFixture.messages(player));
        assertEquals(-1, indexOf(((StoreFixture.RecordingConnection) player.getPlayerConnection()).sent, CookieStorePacket.class),
                "no ticket for a transfer that did not happen");
        assertEquals(List.of(), source.events("transfer_issued"), "nor an event for one");
    }

    @Test
    void aTicketWaitsForAnotherLoginOfItsAccountLoadingOnTheShard(Env env) throws Exception {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final FirstLoadHeld held = new FirstLoadHeld(pool);
        final StoreFixture.Shard target = heldTarget(env, held, StoreFixture.FAST);
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE, (_, _) -> {});
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Racer");
        source.store().get(player, PURSE).coins = 9;
        source.store().markDirty(player, PURSE);
        final byte[] good = transferOut(env, issuer, player);
        final long released = TransferTicket.decode(good, KEYS).stateVersion();
        // The client follows the transfer packet and leaves the source
        player.getPlayerConnection().disconnect();
        await(env, player::isRemoved, "the transferred player leaves the source");

        // An ordinary login of the same account has taken the lease and is still loading
        final Player ordinary = connect(env.process(), account, "Racer");
        final CompletableFuture<Boolean> ordinaryLoaded = CompletableFuture.supplyAsync(
                () -> target.store().load(ordinary), Thread.ofVirtual()::start);
        assertTrue(held.holding.await(5, TimeUnit.SECONDS), "the ordinary login is loading");
        final Player arrived = arriving(env, account, good);
        final TicketVerifier verifier = verifier(target);
        final CompletableFuture<Boolean> arrivedLoaded = CompletableFuture.supplyAsync(
                () -> arrive(target, verifier, arrived), Thread.ofVirtual()::start);
        Thread.sleep(300);
        assertEquals(0, count(database, "SELECT count(*) FROM transfer_nonce"),
                "the ticket is not used while another login of its account loads here");

        held.letGo.countDown();
        assertTrue(ordinaryLoaded.get(5, TimeUnit.SECONDS));
        // What the admission gate does with a login whose connection was replaced meanwhile
        target.store().abandon(ordinary);
        assertTrue(arrivedLoaded.get(5, TimeUnit.SECONDS), "the transfer loads once the other login is gone");

        assertEquals(1, count(database, "SELECT count(*) FROM transfer_nonce"));
        assertEquals(List.of(), reasons(target));
        assertEquals(target.sessionId(), row(database, account).lease());
        assertEquals(released, target.store().versionOf(arrived), "the version the ticket was issued at");
        assertEquals(9, target.store().get(arrived, PURSE).coins);
    }

    @Test
    void aStaleTicketDoesNotGiveBackTheLeaseOfALoginOfItsAccountOnTheShard(Env env) throws Exception {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final FirstLoadHeld held = new FirstLoadHeld(pool);
        final PlayerStore.Options shortBudget = new PlayerStore.Options(Duration.ofSeconds(2), 2, 4,
                List.of(Duration.ofMillis(50)), Duration.ofSeconds(1), Duration.ofMillis(50));
        final StoreFixture.Shard target = heldTarget(env, held, shortBudget);
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE, (_, _) -> {});
        final UUID account = UUID.randomUUID();
        final Instance instance = env.createFlatInstance();
        final Player player = online(env, source, instance, account, "Stale");
        final byte[] stale = transferOut(env, issuer, player);
        player.getPlayerConnection().disconnect();
        await(env, player::isRemoved, "the transferred player leaves the source");
        // The character moved on after this ticket was issued
        database.execute("UPDATE character SET version = version + 1 WHERE account_uuid = '" + account + "'");

        final Player ordinary = connect(env.process(), account, "Stale");
        final CompletableFuture<Boolean> ordinaryLoaded = CompletableFuture.supplyAsync(
                () -> target.store().load(ordinary), Thread.ofVirtual()::start);
        assertTrue(held.holding.await(5, TimeUnit.SECONDS), "the ordinary login is loading");
        final Player arrived = arriving(env, account, stale);
        final TicketVerifier verifier = verifier(target);
        final CompletableFuture<Boolean> arrivedLoaded = CompletableFuture.supplyAsync(
                () -> arrive(target, verifier, arrived), Thread.ofVirtual()::start);
        Thread.sleep(300);
        held.letGo.countDown();
        assertTrue(ordinaryLoaded.get(5, TimeUnit.SECONDS));
        target.store().admitted(ordinary);
        spawn(env.process(), ordinary, instance);

        assertFalse(arrivedLoaded.get(5, TimeUnit.SECONDS), "the stale ticket does not get in");
        assertEquals(target.sessionId(), row(database, account).lease(), "the login that plays keeps its lease");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(arrived), "turned away as the account is playing here");
        assertEquals(List.of(), claimed, "a refused ticket is no arrival");
        final CompletableFuture<PlayerStore.SaveCount> saved = target.store().saveOnline();
        await(env, saved::isDone, "the save of the login that plays");
        assertEquals(new PlayerStore.SaveCount(1, 1), saved.join(), "and its next save is not fenced");
    }

    private static byte[] nonce(int seed) {
        final byte[] nonce = new byte[16];
        nonce[0] = (byte) seed;
        return nonce;
    }

    @Test
    void aTicketArrivingWhileAnAbandonedLoginGivesItsLeaseBackKeepsTheLeaseItClaims(Env env) throws Exception {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final StoreFixture.ReleaseHeld backend = new StoreFixture.ReleaseHeld(pool);
        final StoreFixture.Shard target = StoreFixture.shard(env, pool, TARGET, true, RedisLink.disabled(TARGET),
                StoreFixture.FAST, StoreFixture.MANUAL, backend);
        shards.add(target);
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE, (_, _) -> {});
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Swapper");
        final byte[] good = transferOut(env, issuer, player);
        final long released = TransferTicket.decode(good, KEYS).stateVersion();
        player.getPlayerConnection().disconnect();
        await(env, player::isRemoved, "the transferred player leaves the source");

        // An ordinary login of the same account loaded on the target, which the admission gate abandons;
        // its release statement is still on its way to the database when the transfer arrives
        final Player ordinary = connect(env.process(), account, "Swapper");
        assertTrue(target.store().load(ordinary), "the ordinary login loads");
        backend.holdNextRelease();
        final CompletableFuture<Void> abandoned = CompletableFuture.runAsync(() -> target.store().abandon(ordinary),
                Thread.ofVirtual()::start);
        assertTrue(backend.releasing.await(5, TimeUnit.SECONDS), "the release is under way");
        final Player arrived = arriving(env, account, good);
        final TicketVerifier verifier = verifier(target);
        final CompletableFuture<Boolean> arrivedLoaded = CompletableFuture.supplyAsync(
                () -> arrive(target, verifier, arrived), Thread.ofVirtual()::start);
        Thread.sleep(300);
        backend.letGo.countDown();
        abandoned.get(5, TimeUnit.SECONDS);
        assertTrue(arrivedLoaded.get(5, TimeUnit.SECONDS), "the transfer loads");

        assertEquals(target.sessionId(), row(database, account).lease(), "the transfer holds the lease its ticket claimed");
        assertEquals(1, count(database, "SELECT count(*) FROM transfer_nonce"));
        assertEquals(released, target.store().versionOf(arrived), "the version the ticket was issued at");
    }
}
