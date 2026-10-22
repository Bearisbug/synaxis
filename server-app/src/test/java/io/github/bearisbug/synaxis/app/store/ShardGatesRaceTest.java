package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.app.shard.HeartbeatReader;
import io.github.bearisbug.synaxis.app.shard.HeartbeatRow;
import io.github.bearisbug.synaxis.app.shard.ShardDirectory;
import io.github.bearisbug.synaxis.app.shard.ShardGates;
import io.github.bearisbug.synaxis.app.shard.ShardServiceImpl;
import io.github.bearisbug.synaxis.app.shard.ShardTransferResult;
import io.github.bearisbug.synaxis.app.transfer.TicketIssuer;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two sends of one player at once on a shard: the gates, the shard service, the ticket issuer and the player store of a
 * shard wired as {@code Main#serveMap} wires them, on 4 grid tick threads, a store without a database. The heartbeat
 * reads are held by the test, so that two sends of one player reach {@code PlayerStore#transferOut} from two threads at
 * once, or never answer. A read of the heartbeats of a region answers the walks in of a second
 * ({@code docs/DESIGN-M3.md} {@code REQ-M3-033}): the two tests that count the reads wait 1,000 ms after a read was
 * done before the next walk in.
 */
class ShardGatesRaceTest {

    private static final String SOURCE = "r0-c1";
    private static final String TARGET = "r1-c1";
    private static final TransferKeys KEYS = TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
    /** The gate of r0, a frame around x 4..8 next to the spawn at x 0. */
    private static final ShardDirectory DIRECTORY = new ShardDirectory("synth", List.of(
            new ShardDirectory.Region("r0", new SynxManifest.Bounds(-8, -8, 7, 7), List.of(new ShardDirectory.Gate("r0-east",
                    new ShardDirectory.Frame(4, 0, -4, 8, 255, 4), "r1", new Pos(0.5, 42, 0.5)))),
            new ShardDirectory.Region("r1", new SynxManifest.Bounds(8, -8, 23, 7), List.of())),
            List.of(new ShardDirectory.Channel(SOURCE, "r0", 1, "127.0.0.1", 25601, 200),
                    new ShardDirectory.Channel(TARGET, "r1", 1, "127.0.0.1", 25602, 200)),
            SOURCE);
    private static final double OUTSIDE = 1.5, IN_FRAME = 6.5;
    private static final HeartbeatRow OPEN = new HeartbeatRow(TARGET, "r1", 1, "127.0.0.1", 25602, 0, 200, true, true);
    private static final HeartbeatRow FULL = new HeartbeatRow(TARGET, "r1", 1, "127.0.0.1", 25602, 200, 200, true, true);

    /** Heartbeat reads the test answers: held until released, never, or at once. */
    private static final class Heartbeats implements HeartbeatReader {
        final List<CompletableFuture<List<HeartbeatRow>>> regionReads = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<@Nullable HeartbeatRow>> shardReads = new CopyOnWriteArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        volatile CountDownLatch asked = new CountDownLatch(0);
        volatile boolean hold = true;
        volatile boolean neverFirst;
        volatile HeartbeatRow answer = OPEN;

        @Override
        public CompletableFuture<List<HeartbeatRow>> region(String mapId, String regionId) {
            final int read = reads.incrementAndGet();
            final CompletableFuture<List<HeartbeatRow>> future = new CompletableFuture<>();
            if (neverFirst && read == 1) return future;
            if (!hold) return CompletableFuture.completedFuture(List.of(answer));
            regionReads.add(future);
            asked.countDown();
            return future;
        }

        @Override
        public CompletableFuture<@Nullable HeartbeatRow> shard(String mapId, String shardId) {
            reads.incrementAndGet();
            if (!hold) return CompletableFuture.completedFuture(answer);
            final CompletableFuture<@Nullable HeartbeatRow> future = new CompletableFuture<>();
            shardReads.add(future);
            asked.countDown();
            return future;
        }
    }

    /** The memory backend, whose next write waits once asked to until the test lets it go. */
    private static final class WriteHeld implements StoreBackend {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        volatile @Nullable Thread writer;
        private final AtomicBoolean holdNext = new AtomicBoolean();
        private final MemoryBackend memory = new MemoryBackend();

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
                    boolean _ = letGo.await(10, TimeUnit.SECONDS);
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

    private final List<String> ledger = new CopyOnWriteArrayList<>();
    private final Heartbeats heartbeats = new Heartbeats();
    private final WriteHeld backend = new WriteHeld();
    private Env env;
    private Instance instance;
    private PlayerStore store;
    private ShardServiceImpl service;

    @BeforeEach
    void start() {
        env = Env.create(settings -> settings.dispatcherThreads(4)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID)
                .threadGridExponent(1));
        instance = env.createFlatInstance();
        store = new PlayerStore(env.process(), backend, RedisLink.disabled(SOURCE), SOURCE, false, StoreFixture.FAST,
                (_, _) -> {});
        final EventNode<Event> node = EventNode.all("store-" + SOURCE);
        env.process().eventHandler().addChild(node);
        store.attach(node);
        final TicketIssuer issuer = new TicketIssuer(store, KEYS, new TransferCooldowns(5_000, new AtomicLong(1_000_000)::get),
                SOURCE, (event, attributes) -> ledger.add(event + " " + attributes));
        service = new ShardServiceImpl(DIRECTORY, heartbeats, issuer);
        new ShardGates(DIRECTORY, service, (event, attributes) -> ledger.add(event + " " + attributes), store::isOnline)
                .attach(instance);
    }

    @AfterEach
    void stop() {
        env.close();
    }

    /** A gate walk in and a GM's {@code transferTo} of one player reach the ticket issuer at once: one ticket. */
    @RepeatedTest(5)
    void aGateAndAGmSendingOnePlayerAtOnceSignOneTicket() throws Exception {
        final Player player = online(UUID.randomUUID(), "Racer");
        heartbeats.asked = new CountDownLatch(2);
        walk(player, OUTSIDE);
        walk(player, IN_FRAME);
        final CompletableFuture<ShardTransferResult> gm = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            var _ = service.transferTo(player, TARGET).whenComplete((result, error) -> {
                if (error != null) gm.completeExceptionally(error);
                else gm.complete(result);
            });
        });
        assertTrue(heartbeats.asked.await(5, TimeUnit.SECONDS), "both reads are held: " + heartbeats.reads.get());

        // Both answers at the same instant, on two threads: each goes on to TicketIssuer#transfer on its own
        final CyclicBarrier together = new CyclicBarrier(2);
        final Thread gate = Thread.ofVirtual().start(() -> {
            awaitBarrier(together);
            heartbeats.regionReads.getFirst().complete(List.of(OPEN));
        });
        final Thread command = Thread.ofVirtual().start(() -> {
            awaitBarrier(together);
            heartbeats.shardReads.getFirst().complete(OPEN);
        });
        gate.join();
        command.join();
        await(env, () -> gm.isDone() && count("transfer_issued ") == 1
                && (gm.join() != ShardTransferResult.SENT || count("transfer_refused ") == 1), "both sends are decided");

        final ShardTransferResult byGm = gm.join();
        final boolean gateRefused = count("transfer_refused ") == 1;
        assertNotEquals(byGm == ShardTransferResult.SENT, !gateRefused, "exactly one send won: gm " + byGm + ", " + ledger);
        if (gateRefused) assertTrue(ledger.stream().anyMatch(line -> line.contains("result=not_online")), ledger.toString());
        else assertEquals(ShardTransferResult.NOT_ONLINE, byGm);
        assertEquals(1, count("transfer_issued "), "tickets signed: " + ledger);
        assertEquals(1, sent(player, CookieStorePacket.class), "tickets stored on the client");
        assertEquals(1, sent(player, TransferPacket.class), "transfer packets");
        System.out.println("race won by " + (gateRefused ? "gm" : "gate"));
    }

    /** A heartbeat read that never answers frees the gate once the shard service gives it up: no stuck transfer. */
    @RepeatedTest(5)
    void aHeartbeatReadThatNeverAnswersDoesNotHoldTheGateForGood() {
        heartbeats.hold = false;
        heartbeats.neverFirst = true;
        heartbeats.answer = FULL;
        final Player player = online(UUID.randomUUID(), "Stuck");
        walk(player, OUTSIDE);
        walk(player, IN_FRAME);
        walk(player, OUTSIDE);
        walk(player, IN_FRAME);
        assertEquals(1, heartbeats.reads.get(), "a second walk in while the first transfer is under way reads nothing");

        await(env, () -> count("result=directory_unavailable") == 1, "the read is given up");
        pastTheSecondOfTheLastRead();
        walk(player, OUTSIDE);
        walk(player, IN_FRAME);
        await(env, () -> count("result=full") == 1, "the next walk in reads again");
        assertEquals(2, heartbeats.reads.get());
    }

    /**
     * The player leaves while its gate transfer writes, and the same account logs in again on this shard: the old
     * connection gets no ticket, its transfer is decided, and the new connection walks through the gate.
     */
    @RepeatedTest(5)
    void aPlayerWhoLeavesWhileItsTransferWritesAndComesBackWalksThroughAgain() throws Exception {
        heartbeats.hold = false;
        final UUID account = UUID.randomUUID();
        final Player before = online(account, "Returner");
        backend.holdNext.set(true);
        walk(before, OUTSIDE);
        walk(before, IN_FRAME);
        await(env, () -> backend.writing.getCount() == 0, "the transfer write is under way");
        before.getPlayerConnection().disconnect();
        await(env, before::isRemoved, "the first connection is gone");

        final Player after = StoreFixture.connect(env.process(), account, "Returner");
        final CompletableFuture<Boolean> loaded = CompletableFuture.supplyAsync(() -> store.load(after),
                Thread.ofVirtual()::start);
        backend.letGo.countDown();
        Objects.requireNonNull(backend.writer).join();
        assertTrue(loaded.get(10, TimeUnit.SECONDS), "the new connection loads once the old write let go");
        store.admitted(after);
        StoreFixture.spawn(env.process(), after, instance);
        env.tick();
        pastTheSecondOfTheLastRead();
        walk(after, OUTSIDE);
        walk(after, IN_FRAME);
        await(env, () -> count("transfer_issued ") == 1 && count("result=not_online") == 1,
                "the new connection is sent, the old transfer is decided");

        assertEquals(0, sent(before, CookieStorePacket.class) + sent(before, TransferPacket.class),
                "no ticket for the connection that left");
        assertEquals(1, sent(after, CookieStorePacket.class), "one ticket for the new connection");
        assertEquals(1, sent(after, TransferPacket.class));
        assertEquals(2, heartbeats.reads.get(), "one read per walk in");
    }

    private Player online(UUID account, String name) {
        final Player player = StoreFixture.connect(env.process(), account, name);
        assertTrue(store.load(player), "loaded");
        store.admitted(player);
        StoreFixture.spawn(env.process(), player, instance);
        env.tick();
        assertInstanceOf(TickThread.class, player.acquirable().assignedThread(), "owned by a tick thread");
        return player;
    }

    /** A position packet of the player, handled on the tick thread that owns it, as the engine does. */
    private void walk(Player player, double x) {
        player.scheduler().scheduleNextTick(() -> env.process().eventHandler()
                .call(new PlayerMoveEvent(player, new Pos(x, 42, 0.5), true)));
        env.tick();
    }

    /** A read of the heartbeats answers the walks in of the second after it was done: wait that second out. */
    private static void pastTheSecondOfTheLastRead() {
        try {
            TimeUnit.MILLISECONDS.sleep(1_100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private long count(String needle) {
        return ledger.stream().filter(line -> line.contains(needle)).count();
    }

    private static long sent(Player player, Class<?> type) {
        final List<SendablePacket> sent = ((StoreFixture.RecordingConnection) player.getPlayerConnection()).sent;
        return sent.stream().filter(type::isInstance).count();
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
