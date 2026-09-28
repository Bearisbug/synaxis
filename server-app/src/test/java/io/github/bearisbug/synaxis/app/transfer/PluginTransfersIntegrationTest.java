package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.OutgoingTransferEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import io.github.bearisbug.synaxis.thread.TickThread;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin face of transfers ({@code docs/DESIGN-PROD.md} {@code API-PROD-021}): a vanilla
 * transfer only for a player in the play state, decided on the player's tick thread and on the
 * cooldown table the ticket side uses, and cookies only in the plugin's own namespace.
 */
@EnvTest
class PluginTransfersIntegrationTest {

    private static final String PLUGIN = "probe";
    private static final long START = 1_000_000;
    private static final Pos SPAWN = new Pos(0, 42, 0);
    /**
     * Well under {@link PluginTransfers#TICK_TIMEOUT_MILLIS}: a false the timeout decided does not
     * pass for one the tick decided.
     */
    private static final Duration DECIDED = Duration.ofSeconds(2);

    private final AtomicLong clock = new AtomicLong(START);
    private final TransferCooldowns cooldowns = new TransferCooldowns(5_000, clock::get);
    private final PluginTransfers transfers = new PluginTransfers(PLUGIN, cooldowns);

    @Test
    void aTransferStartsTheCooldownAndTheCooldownRefusesTheNextOne(Env env) {
        final TestConnection connection = env.createConnection();
        final Player player = spawn(env, connection);

        final Collector<TransferPacket> first = connection.trackIncoming(TransferPacket.class);
        assertTrue(decided(env, transfers.transfer(player, "lobby.example", 25565)));
        first.assertSingle(packet -> assertEquals(new TransferPacket("lobby.example", 25565), packet));
        assertTrue(cooldowns.coolingDown(player.getUuid()), "the ticket side sees the plugin's transfer");

        clock.set(START + 4_999);
        final Collector<TransferPacket> refused = connection.trackIncoming(TransferPacket.class);
        assertFalse(decided(env, transfers.transfer(player, "lobby.example", 25565)));
        refused.assertEmpty();

        clock.set(START + 5_000);
        final Collector<TransferPacket> second = connection.trackIncoming(TransferPacket.class);
        assertTrue(decided(env, transfers.transfer(player, "lobby.example", 25565)));
        second.assertSingle();
    }

    @Test
    void aTicketThatBroughtThePlayerInCoolsThePluginDown(Env env) {
        final TestConnection connection = env.createConnection();
        final Player player = spawn(env, connection);
        final Collector<TransferPacket> sent = connection.trackIncoming(TransferPacket.class);
        // What TicketVerifier#claim records for a ticket issued at START
        cooldowns.record(player.getUuid(), START);

        clock.set(START + 3_000);
        assertFalse(decided(env, transfers.transfer(player, "lobby.example", 25565)));
        clock.set(START + 6_000);
        assertTrue(decided(env, transfers.transfer(player, "lobby.example", 25565)));
        sent.assertSingle();
    }

    @Test
    void aPlayerOutsideThePlayStateIsNotTransferredAndNotCooled(Env env) {
        final RecordingConnection configuring = new RecordingConnection(env.process(), ConnectionState.CONFIGURATION);
        final Player configuringPlayer = player(configuring);
        final RecordingConnection gone = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player gonePlayer = player(gone);
        gone.disconnect();

        final CompletableFuture<Boolean> ofConfiguring = transfers.transfer(configuringPlayer, "lobby.example", 25565);
        final CompletableFuture<Boolean> ofGone = transfers.transfer(gonePlayer, "lobby.example", 25565);
        assertTrue(ofConfiguring.isDone() && ofGone.isDone(), "decided on the calling thread, nothing waits for a tick");
        assertFalse(ofConfiguring.join());
        assertFalse(ofGone.join());
        assertEquals(List.of(), configuring.sent(TransferPacket.class));
        assertEquals(List.of(), gone.sent(TransferPacket.class));
        assertFalse(cooldowns.coolingDown(configuringPlayer.getUuid()));
        assertFalse(cooldowns.coolingDown(gonePlayer.getUuid()));
    }

    @Test
    void aPlayerWhoLeavesBeforeItsNextTickIsNotTransferredAndTheFutureStillCompletes(Env env)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Left between the call and its tick: the tick checks the play state again
        final TestConnection connection = env.createConnection();
        final Player leaving = spawn(env, connection);
        final Collector<TransferPacket> sentToLeaving = connection.trackIncoming(TransferPacket.class);
        final CompletableFuture<Boolean> ofLeaving = transfers.transfer(leaving, "lobby.example", 25565);
        leaving.getPlayerConnection().disconnect();
        assertFalse(decided(env, ofLeaving));
        sentToLeaving.assertEmpty();
        assertFalse(cooldowns.coolingDown(leaving.getUuid()));

        // In the play state but never ticked, as a player removed before its next tick
        final RecordingConnection unticked = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player gone = player(unticked);
        final CompletableFuture<Boolean> ofGone = transfers.transfer(gone, "lobby.example", 25565);
        assertFalse(ofGone.get(PluginTransfers.TICK_TIMEOUT_MILLIS + 2_000, TimeUnit.MILLISECONDS));
        assertEquals(List.of(), unticked.sent(TransferPacket.class));
        assertFalse(cooldowns.coolingDown(gone.getUuid()));
    }

    @Test
    void aBlankHostOrAPortOutOfRangeIsRefusedBeforeAnythingHappens(Env env) {
        final TestConnection connection = env.createConnection();
        final Player player = spawn(env, connection);
        final Collector<TransferPacket> sent = connection.trackIncoming(TransferPacket.class);

        assertThrows(IllegalArgumentException.class, () -> transfers.transfer(player, " ", 25565));
        assertThrows(IllegalArgumentException.class, () -> transfers.transfer(player, "lobby.example", 0));
        assertThrows(IllegalArgumentException.class, () -> transfers.transfer(player, "lobby.example", 65_536));
        assertTrue(decided(env, transfers.transfer(player, "lobby.example", 65_535)), "the refused ones started no cooldown");
        sent.assertSingle(packet -> assertEquals(65_535, packet.port()));
    }

    @Test
    void aListenerOfTheTransferRunsOnThePlayersTickThreadAndMayMoveThePlayer(Env env) throws InterruptedException {
        final TestConnection connection = env.createConnection();
        final Player player = spawn(env, connection);
        final Collector<TransferPacket> sent = connection.trackIncoming(TransferPacket.class);
        final Pos moved = new Pos(3, 42, 3);
        final AtomicReference<Thread> calledOn = new AtomicReference<>();
        final AtomicReference<Throwable> failed = new AtomicReference<>();
        env.process().eventHandler().addListener(OutgoingTransferEvent.class, event -> {
            calledOn.set(Thread.currentThread());
            try {
                var _ = event.getPlayer().teleport(moved);
            } catch (Throwable throwable) {
                failed.set(throwable);
            }
        });

        // From a thread that is not a tick thread, as a runAsync body after a database lookup
        final AtomicReference<CompletableFuture<Boolean>> asked = new AtomicReference<>();
        Thread.ofVirtual().start(() -> asked.set(transfers.transfer(player, "lobby.example", 25565))).join();
        final AtomicReference<Thread> completedOn = new AtomicReference<>();
        final CompletableFuture<Boolean> observed = asked.get().whenComplete((_, _) -> completedOn.set(Thread.currentThread()));

        assertTrue(decided(env, observed));
        assertNull(failed.get(), "the listener owns the player");
        assertInstanceOf(TickThread.class, calledOn.get(), "the event is called on a tick thread");
        assertSame(player.acquirable().assignedThread(), calledOn.get(), "on the one that owns the player");
        assertSame(calledOn.get(), completedOn.get(), "and the future completes there");
        assertEquals(moved, player.getPosition());
        sent.assertSingle();
        assertTrue(cooldowns.coolingDown(player.getUuid()));
    }

    @Test
    void aCancelledTransferSendsNothingAndStartsNoCooldown(Env env) {
        final TestConnection connection = env.createConnection();
        final Player player = spawn(env, connection);
        final AtomicBoolean cancel = new AtomicBoolean(true);
        env.process().eventHandler().addListener(OutgoingTransferEvent.class, event -> {
            if (cancel.get()) event.setCancelled(true);
            else event.setPort(25566);
        });

        final Collector<TransferPacket> cancelled = connection.trackIncoming(TransferPacket.class);
        assertFalse(decided(env, transfers.transfer(player, "lobby.example", 25565)));
        cancelled.assertEmpty();
        assertFalse(cooldowns.coolingDown(player.getUuid()), "a cancelled transfer starts no cooldown");

        cancel.set(false);
        final Collector<TransferPacket> sent = connection.trackIncoming(TransferPacket.class);
        assertTrue(decided(env, transfers.transfer(player, "lobby.example", 25565)), "so the next one is not refused");
        sent.assertSingle(packet -> assertEquals(new TransferPacket("lobby.example", 25566), packet));
    }

    @Test
    void aCookieOutsideThePluginsNamespaceIsRefusedAndTheTicketAmongThem(Env env) {
        final RecordingConnection connection = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player player = player(connection);
        final Key ticket = Key.key(TransferTicket.COOKIE);

        assertThrows(IllegalArgumentException.class, () -> transfers.storeCookie(player, ticket, new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> transfers.fetchCookie(player, ticket));
        assertThrows(IllegalArgumentException.class,
                () -> transfers.storeCookie(player, Key.key("other", "state"), new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> transfers.fetchCookie(player, Key.key("other", "state")));
        assertEquals(List.of(), connection.sent(CookieStorePacket.class));
        assertEquals(List.of(), connection.sent(CookieRequestPacket.class));
    }

    @Test
    void aCookieOfAtMost5120BytesIsStoredAndALongerOneSendsNothing(Env env) {
        final RecordingConnection connection = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player player = player(connection);
        final Key key = Key.key(PLUGIN, "state");

        transfers.storeCookie(player, key, new byte[5_120]);
        assertThrows(IllegalArgumentException.class, () -> transfers.storeCookie(player, key, new byte[5_121]));
        final List<CookieStorePacket> stored = connection.sent(CookieStorePacket.class);
        assertEquals(1, stored.size());
        assertEquals("probe:state", stored.getFirst().key());
        assertEquals(5_120, stored.getFirst().value().length);
    }

    @Test
    void aFetchCompletesWithTheAnswerWithNullForNoCookieAndTimesOutWithoutAnAnswer(Env env)
            throws ExecutionException, InterruptedException, TimeoutException {
        final RecordingConnection connection = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player player = player(connection);
        final Key key = Key.key(PLUGIN, "state");

        final CompletableFuture<byte[]> answered = transfers.fetchCookie(player, key);
        connection.receiveCookieResponse("probe:state", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, answered.get(1, TimeUnit.SECONDS));

        final CompletableFuture<byte[]> none = transfers.fetchCookie(player, key);
        connection.receiveCookieResponse("probe:state", null);
        assertNull(none.get(1, TimeUnit.SECONDS));

        final CompletableFuture<byte[]> unanswered = transfers.fetchCookie(player, key);
        assertEquals(List.of(new CookieRequestPacket("probe:state"), new CookieRequestPacket("probe:state"),
                new CookieRequestPacket("probe:state")), connection.sent(CookieRequestPacket.class));
        final ExecutionException late = assertThrows(ExecutionException.class,
                () -> unanswered.get(PluginTransfers.COOKIE_TIMEOUT_MILLIS + 2_000, TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, late.getCause());
    }

    @Test
    void wasTransferredIsTheHandshakeIntentOfTheConnection(Env env) {
        final RecordingConnection connection = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player player = player(connection);
        assertFalse(transfers.wasTransferred(player));
        connection.markTransferred(true);
        assertTrue(transfers.wasTransferred(player));
    }

    private static Player player(PlayerConnection connection) {
        return new Player(connection, new GameProfile(UUID.randomUUID(), "probe"));
    }

    /** A player in the world, owned by a tick thread: one tick has run since it spawned. */
    private static Player spawn(Env env, TestConnection connection) {
        final Player player = connection.connect(env.createFlatInstance(), SPAWN);
        env.tick();
        return player;
    }

    /** Ticks until the player's tick thread has decided the transfer. */
    private static boolean decided(Env env, CompletableFuture<Boolean> transfer) {
        assertTrue(env.tickWhile(() -> !transfer.isDone(), DECIDED), "decided by the player's tick");
        return transfer.join();
    }

    /** A connection that keeps what the server sends it and sits in the state the test puts it in. */
    private static final class RecordingConnection extends PlayerConnection {

        private final List<Object> sent = new CopyOnWriteArrayList<>();

        RecordingConnection(ServerProcess process, ConnectionState state) {
            super(process);
            setServerState(state);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            sent.add(packet);
        }

        <T> List<T> sent(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        }
    }
}
