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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * is all §26 asks of the 3D acceptance: no M3 {@code ShardService} is needed.
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
                new TransferCooldowns(5_000, targetClock::get), (event, attributes) -> target.ledger().add(event + " " + attributes));
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
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE);
        final TicketVerifier verifier = verifier(target);
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Traveller");
        source.store().get(player, PURSE).coins = 11;
        source.store().markDirty(player, PURSE);

        final byte[] good = transferOut(env, issuer, player);
        final TransferTicket ticket = TransferTicket.decode(good, KEYS);
        assertEquals(account, ticket.playerUuid());
        assertEquals(row(database, account).version(), ticket.stateVersion(), "the version the release wrote");
        assertNull(row(database, account).lease(), "released before the ticket was sent");

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
            assertFalse(verifier.verify(refused));
            assertEquals(List.of("synaxis:transfer-rejected"), kicks(refused));
        }
        assertEquals(List.of("bad-signature", "expired", "wrong-target", "uuid-mismatch"), reasons(target));

        final Player arrived = arriving(env, account, good);
        assertTrue(verifier.verify(arrived), "the good ticket passes");
        assertEquals(target.sessionId(), row(database, account).lease(), "the target holds the character now");
        assertTrue(target.store().load(arrived), "and loads it like any login");
        assertEquals(11, target.store().get(arrived, PURSE).coins);
        assertEquals(ticket.stateVersion(), target.store().versionOf(arrived));

        final Player replayed = arriving(env, account, good);
        assertFalse(verifier.verify(replayed));
        assertEquals("nonce-reused", reasons(target).getLast());
        assertEquals(List.of("synaxis:transfer-rejected"), kicks(replayed));
        assertEquals(1, count(database, "SELECT count(*) FROM transfer_nonce"), "only the good ticket used its nonce");
    }

    @Test
    void aTicketWhoseVersionTheCharacterIsNotAtIsRefusedAndTheLeaseGivenBack(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final StoreFixture.Shard target = shard(env, TARGET);
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE);
        final UUID account = UUID.randomUUID();
        final Player player = online(env, source, env.createFlatInstance(), account, "Drifter");
        final byte[] good = transferOut(env, issuer, player);
        database.execute("UPDATE character SET version = version + 1 WHERE account_uuid = '" + account + "'");

        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(TicketVerifier.class)).addAppender(log);
        final Player arrived = arriving(env, account, good);
        try {
            assertFalse(verifier(target).verify(arrived));
        } finally {
            ((Logger) LoggerFactory.getLogger(TicketVerifier.class)).detachAppender(log);
        }

        assertEquals(List.of("version-mismatch"), reasons(target));
        assertEquals(List.of("synaxis:transfer-rejected"), kicks(arrived));
        assertEquals(1, log.list.stream().filter(event -> event.getLevel() == Level.ERROR).count(), "one consistency ERROR");
        assertNull(row(database, account).lease(), "the lease taken for the check goes back");
    }

    @Test
    void theCooldownOfATransferInCarriesToTheTargetShard(Env env) throws SQLException {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final StoreFixture.Shard target = shard(env, TARGET);
        final long t = sourceClock.get();
        targetClock.set(t);
        final TicketIssuer outOfSource = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE);
        final TransferCooldowns targetCooldowns = new TransferCooldowns(5_000, targetClock::get);
        final TicketVerifier verifier = new TicketVerifier(pool.pool(), target::sessionId, TARGET, KEYS, targetCooldowns,
                (event, attributes) -> target.ledger().add(event + " " + attributes));
        final TicketIssuer outOfTarget = new TicketIssuer(target.store(), KEYS, targetCooldowns, TARGET);
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
        assertTrue(verifier.verify(arrived));
        assertTrue(target.store().load(arrived));
        target.store().admitted(arrived);
        spawn(env.process(), arrived, env.createFlatInstance());
        final long versionBefore = row(database, account).version();

        targetClock.set(t + 3_000);
        assertEquals(TransferOutcome.COOLDOWN, outOfTarget.transfer(arrived, SOURCE, "127.0.0.1", 25631).join());
        assertEquals(versionBefore, row(database, account).version(), "a refused transfer writes nothing");
        assertEquals(target.sessionId(), row(database, account).lease());

        targetClock.set(t + 6_000);
        final CompletableFuture<TransferOutcome> later = outOfTarget.transfer(arrived, SOURCE, "127.0.0.1", 25631);
        await(env, later::isDone, "the second transfer completes");
        assertEquals(TransferOutcome.SENT, later.join());
    }

    @Test
    void aTransferThatCannotWriteLeavesThePlayerWhereItIs(Env env) throws Exception {
        final StoreFixture.Shard source = shard(env, SOURCE);
        final TicketIssuer issuer = new TicketIssuer(source.store(), KEYS, new TransferCooldowns(5_000, sourceClock::get), SOURCE);
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
    }

    private static byte[] nonce(int seed) {
        final byte[] nonce = new byte[16];
        nonce[0] = (byte) seed;
        return nonce;
    }
}
