package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin face of transfers ({@code docs/DESIGN-PROD.md} {@code API-PROD-021}): a vanilla
 * transfer only for a player in the play state and on the cooldown table the ticket side uses,
 * and cookies only in the plugin's own namespace.
 */
@EnvTest
class PluginTransfersIntegrationTest {

    private static final String PLUGIN = "probe";
    private static final long START = 1_000_000;

    private final AtomicLong clock = new AtomicLong(START);
    private final TransferCooldowns cooldowns = new TransferCooldowns(5_000, clock::get);
    private final PluginTransfers transfers = new PluginTransfers(PLUGIN, cooldowns);

    @Test
    void aTransferStartsTheCooldownAndTheCooldownRefusesTheNextOne(Env env) {
        final RecordingConnection connection = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player player = player(connection);

        assertTrue(transfers.transfer(player, "lobby.example", 25565));
        assertEquals(List.of(new TransferPacket("lobby.example", 25565)), connection.sent(TransferPacket.class));
        assertTrue(cooldowns.coolingDown(player.getUuid()), "the ticket side sees the plugin's transfer");

        clock.set(START + 4_999);
        assertFalse(transfers.transfer(player, "lobby.example", 25565));
        assertEquals(1, connection.sent(TransferPacket.class).size(), "a refused transfer sends nothing");

        clock.set(START + 5_000);
        assertTrue(transfers.transfer(player, "lobby.example", 25565));
        assertEquals(2, connection.sent(TransferPacket.class).size());
    }

    @Test
    void aTicketThatBroughtThePlayerInCoolsThePluginDown(Env env) {
        final RecordingConnection connection = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player player = player(connection);
        // What TicketVerifier#claim records for a ticket issued at START
        cooldowns.record(player.getUuid(), START);

        clock.set(START + 3_000);
        assertFalse(transfers.transfer(player, "lobby.example", 25565));
        clock.set(START + 6_000);
        assertTrue(transfers.transfer(player, "lobby.example", 25565));
        assertEquals(1, connection.sent(TransferPacket.class).size());
    }

    @Test
    void aPlayerOutsideThePlayStateIsNotTransferredAndNotCooled(Env env) {
        final RecordingConnection configuring = new RecordingConnection(env.process(), ConnectionState.CONFIGURATION);
        final Player configuringPlayer = player(configuring);
        final RecordingConnection gone = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player gonePlayer = player(gone);
        gone.disconnect();

        assertFalse(transfers.transfer(configuringPlayer, "lobby.example", 25565));
        assertFalse(transfers.transfer(gonePlayer, "lobby.example", 25565));
        assertEquals(List.of(), configuring.sent(TransferPacket.class));
        assertEquals(List.of(), gone.sent(TransferPacket.class));
        assertFalse(cooldowns.coolingDown(configuringPlayer.getUuid()));
        assertFalse(cooldowns.coolingDown(gonePlayer.getUuid()));

        configuring.setServerState(ConnectionState.PLAY);
        assertTrue(transfers.transfer(configuringPlayer, "lobby.example", 25565), "no cooldown was started");
    }

    @Test
    void aBlankHostOrAPortOutOfRangeIsRefusedBeforeAnythingHappens(Env env) {
        final RecordingConnection connection = new RecordingConnection(env.process(), ConnectionState.PLAY);
        final Player player = player(connection);

        assertThrows(IllegalArgumentException.class, () -> transfers.transfer(player, " ", 25565));
        assertThrows(IllegalArgumentException.class, () -> transfers.transfer(player, "lobby.example", 0));
        assertThrows(IllegalArgumentException.class, () -> transfers.transfer(player, "lobby.example", 65_536));
        assertEquals(List.of(), connection.sent(TransferPacket.class));
        assertFalse(cooldowns.coolingDown(player.getUuid()));
        assertTrue(transfers.transfer(player, "lobby.example", 65_535));
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
