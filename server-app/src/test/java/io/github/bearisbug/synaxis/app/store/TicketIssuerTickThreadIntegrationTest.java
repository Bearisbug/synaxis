package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.app.transfer.TicketIssuer;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.OutgoingTransferEvent;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source side of a transfer on the tick thread that owns the player ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-016}, {@code API-PROD-028} issuing ④; {@code docs/DESIGN-M3.md} {@code API-M3-010}):
 * the write gives the lease back on a {@code Syn-Store-Write} thread, the ticket goes out at the
 * player's next tick, with {@link OutgoingTransferEvent} called between the cookie and the transfer
 * and {@code EVT:transfer_issued} written after both. A player that left before that tick is sent no
 * ticket; one still connected is waited for, however late its tick. The store is the one of a process
 * without a database, whose writes run on a {@code Syn-Store-Write} thread all the same.
 */
@EnvTest
class TicketIssuerTickThreadIntegrationTest {

    private static final String SOURCE = "r2-c1";
    private static final String TARGET = "r1-c1";
    private static final TransferKeys KEYS = TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
    private static final Pos MOVED = new Pos(3, 42, 3);
    /** Well over the 5 s after which the issuer looks at a player that did not tick. */
    private static final long GONE_WITHIN_SECONDS = 7;
    /** Over those 5 s, for a player that is still connected. */
    private static final long STALLED_SECONDS = 6;

    private final TransferCooldowns cooldowns = new TransferCooldowns(5_000, new AtomicLong(1_000_000)::get);
    private final List<String> ledger = new CopyOnWriteArrayList<>();

    /** The memory backend, whose next write waits once asked to until the test lets it go. */
    private static final class WriteHeld implements StoreBackend {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        /** The thread the held write runs on. */
        volatile @Nullable Thread writer;
        private final AtomicBoolean holdNext = new AtomicBoolean();
        private final MemoryBackend memory = new MemoryBackend();

        void holdNextWrite() {
            holdNext.set(true);
        }

        @Override
        public LoadOutcome load(UUID account, String name, long sessionId) {
            return memory.load(account, name, sessionId);
        }

        @Override
        public void write(long sessionId, List<Write> writes) throws FencedException {
            if (holdNext.compareAndSet(true, false)) {
                writer = Thread.currentThread();
                writing.countDown();
                try {
                    letGo.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            memory.write(sessionId, writes);
        }

        @Override
        public void release(long characterId, long sessionId) {
            memory.release(characterId, sessionId);
        }

        @Override
        public void releaseAccount(UUID account, long sessionId) {
            memory.releaseAccount(account, sessionId);
        }
    }

    /** A store without a database, told about disconnects as the host's is. */
    private static PlayerStore store(Env env, StoreBackend backend) {
        final PlayerStore store = new PlayerStore(env.process(), backend, RedisLink.disabled(SOURCE), SOURCE, false,
                StoreFixture.FAST, (_, _) -> {});
        final EventNode<Event> node = EventNode.all("store-" + SOURCE);
        env.process().eventHandler().addChild(node);
        store.attach(node);
        return store;
    }

    /** A player in the world, owned by a tick thread: loaded, admitted, spawned and ticked once. */
    private static Player online(Env env, PlayerStore store, String name) {
        final Player player = StoreFixture.connect(env.process(), UUID.randomUUID(), name);
        assertTrue(store.load(player), "loaded");
        store.admitted(player);
        StoreFixture.spawn(env.process(), player, env.createFlatInstance());
        env.tick();
        assertInstanceOf(TickThread.class, player.acquirable().assignedThread(), "owned by a tick thread");
        return player;
    }

    private static List<SendablePacket> sent(Player player) {
        return ((StoreFixture.RecordingConnection) player.getPlayerConnection()).sent;
    }

    private static int indexOf(List<SendablePacket> sent, Class<?> type) {
        for (int i = 0; i < sent.size(); i++) if (type.isInstance(sent.get(i))) return i;
        return -1;
    }

    private TicketIssuer issuer(PlayerStore store) {
        return new TicketIssuer(store, KEYS, cooldowns, SOURCE, (event, attributes) -> ledger.add(event + " " + attributes));
    }

    @Test
    void theTransferEventIsCalledOnThePlayersTickThreadBetweenTheCookieAndTheTransfer(Env env) {
        final PlayerStore store = store(env, new MemoryBackend());
        final Player player = online(env, store, "Traveller");
        final List<SendablePacket> sent = sent(player);
        final AtomicReference<Thread> calledOn = new AtomicReference<>();
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        final List<Boolean> cookieStoredFirst = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(OutgoingTransferEvent.class, event -> {
            calledOn.set(Thread.currentThread());
            cookieStoredFirst.add(indexOf(sent, CookieStorePacket.class) >= 0);
            try {
                var _ = event.getPlayer().teleport(MOVED);
            } catch (Throwable throwable) {
                failed.set(throwable);
            }
        });
        final List<Boolean> transferSentFirst = new CopyOnWriteArrayList<>();
        final TicketIssuer issuer = new TicketIssuer(store, KEYS, cooldowns, SOURCE, (event, attributes) -> {
            transferSentFirst.add(indexOf(sent, TransferPacket.class) >= 0);
            ledger.add(event + " " + attributes);
        });

        final AtomicReference<Thread> completedOn = new AtomicReference<>();
        final CompletableFuture<TransferOutcome> outcome = issuer.transfer(player, TARGET, "127.0.0.1", 25631)
                .whenComplete((_, _) -> completedOn.set(Thread.currentThread()));
        await(env, outcome::isDone, "the transfer completes");

        assertEquals(TransferOutcome.SENT, outcome.join());
        assertInstanceOf(TickThread.class, calledOn.get(), "the event is called on a tick thread, not " + calledOn.get());
        assertSame(player.acquirable().assignedThread(), calledOn.get(), "on the one that owns the player");
        assertNull(failed.get(), "the listener owns the player");
        assertEquals(MOVED, player.getPosition());
        assertSame(calledOn.get(), completedOn.get(), "and the future completes there");
        assertEquals(List.of(true), cookieStoredFirst, "the cookie is stored before the event is called");
        final int cookieAt = indexOf(sent, CookieStorePacket.class);
        assertTrue(cookieAt >= 0 && cookieAt < indexOf(sent, TransferPacket.class), "the cookie is stored before the transfer");
        assertEquals(List.of(true), transferSentFirst, "EVT:transfer_issued is written once the client was told where to go");
        assertEquals(TransferOutcome.COOLDOWN, issuer.transfer(player, TARGET, "127.0.0.1", 25631).join(),
                "the ticket sent started the cooldown");
    }

    @Test
    void aPlayerWhoLeavesBeforeItsTickIsSentNoTicketAndTheFutureStillCompletes(Env env) throws Exception {
        final WriteHeld backend = new WriteHeld();
        final PlayerStore store = store(env, backend);
        final TicketIssuer issuer = issuer(store);

        // Left once the write was over, before its tick: the tick finds the connection closed
        final Player leaving = online(env, store, "Leaving");
        backend.holdNextWrite();
        final CompletableFuture<TransferOutcome> ofLeaving = issuer.transfer(leaving, TARGET, "127.0.0.1", 25631);
        env.tick();
        assertTrue(backend.writing.await(5, TimeUnit.SECONDS), "the write is under way");
        backend.letGo.countDown();
        // The write thread ends after it completed the handoff, which is when the issuer queues its tick
        Objects.requireNonNull(backend.writer).join();
        leaving.getPlayerConnection().disconnect();
        await(env, ofLeaving::isDone, "the transfer is decided at the next tick");
        assertEquals(TransferOutcome.NOT_ONLINE, ofLeaving.join());
        assertNothingSent(issuer, leaving);

        // Removed at the tick that took its snapshot: nothing ticks the player again
        final Player gone = online(env, store, "Gone");
        final CompletableFuture<TransferOutcome> ofGone = issuer.transfer(gone, TARGET, "127.0.0.1", 25631);
        gone.getPlayerConnection().disconnect();
        await(env, gone::isRemoved, "the player leaves");
        assertEquals(TransferOutcome.NOT_ONLINE, ofGone.get(GONE_WITHIN_SECONDS, TimeUnit.SECONDS));
        assertNothingSent(issuer, gone);
        assertEquals(List.of(), ledger, "no EVT:transfer_issued for a ticket nobody was sent");
    }

    @Test
    void aPlayerStillConnectedGetsItsTicketHoweverLateItsTick(Env env) throws InterruptedException {
        final WriteHeld backend = new WriteHeld();
        final PlayerStore store = store(env, backend);
        final Player player = online(env, store, "Stalled");
        backend.holdNextWrite();
        final CompletableFuture<TransferOutcome> outcome = issuer(store).transfer(player, TARGET, "127.0.0.1", 25631);
        env.tick();
        assertTrue(backend.writing.await(5, TimeUnit.SECONDS), "the write is under way");
        backend.letGo.countDown();
        Objects.requireNonNull(backend.writer).join();
        // No tick for longer than the issuer waits before it looks at the connection: the lease is
        // back already, a player left connected without its ticket would be stuck in in_transit
        Thread.sleep(TimeUnit.SECONDS.toMillis(STALLED_SECONDS));
        assertFalse(outcome.isDone(), "a connected player is waited for");
        await(env, outcome::isDone, "the transfer is decided at the late tick");
        assertEquals(TransferOutcome.SENT, outcome.join());
        final List<SendablePacket> sent = sent(player);
        final int cookieAt = indexOf(sent, CookieStorePacket.class);
        assertTrue(cookieAt >= 0 && cookieAt < indexOf(sent, TransferPacket.class), "the ticket went out");
        assertEquals(1, ledger.size(), "one EVT:transfer_issued");
    }

    /** No packet of a ticket, and no cooldown: the issuer asked again checks the cooldown first. */
    private static void assertNothingSent(TicketIssuer issuer, Player player) {
        assertEquals(-1, indexOf(sent(player), CookieStorePacket.class), "no ticket");
        assertEquals(-1, indexOf(sent(player), TransferPacket.class), "no transfer");
        assertEquals(TransferOutcome.NOT_ONLINE, issuer.transfer(player, TARGET, "127.0.0.1", 25631).join(), "no cooldown");
    }
}
