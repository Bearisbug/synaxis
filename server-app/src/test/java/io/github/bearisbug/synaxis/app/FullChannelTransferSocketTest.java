package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.MemoryBackend;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.EncryptionRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §19 「满员频道在读 Cookie 之前拒绝转入」 ({@code docs/DESIGN-M3.md} {@code REQ-M3-017}): a channel
 * whose seats are all taken turns away a connection that comes in with the transfer intent and a
 * valid ticket at its login start, with {@code synaxis:shard-full}, before the encryption request and
 * before the ticket's cookie is asked for. The nonce is not spent and the ledger has nothing of it;
 * only the gate counts it. Once a seat is free the same connection gets the encryption request, or
 * the cookie request and a spent nonce, so the rig shows each of them when it happens.
 * <p>
 * The host is wired as a directory mode shard wires it: the admission gate with a
 * {@link TicketVerifier} on a real PostgreSQL, the event ledger, the engine's gate at L3.
 */
@Timeout(60)
class FullChannelTransferSocketTest {

    private static final String SHARD = "r1-c1";
    private static final TransferKeys KEYS = TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(new byte[32]));
    private static final String NAME = "Traveller";
    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path workingDirectory;

    private TestDatabase database;
    private Database pool;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** The encrypted offline login: a full channel answers the transfer before the encryption request. */
    @Test
    void aFullChannelRefusesATransferBeforeTheEncryptionRequest() throws Exception {
        try (Rig rig = new Rig(new Auth.Offline(true))) {
            final Peer first = rig.connect(Intent.LOGIN, "holder0");
            assertInstanceOf(EncryptionRequestPacket.class, first.read(ConnectionState.LOGIN), "the first seat");
            assertInstanceOf(EncryptionRequestPacket.class, rig.connect(Intent.LOGIN, "holder1").read(ConnectionState.LOGIN),
                    "the second seat");

            final Peer refused = rig.connect(Intent.TRANSFER, NAME);
            assertEquals("synaxis:shard-full", disconnectKey(refused.read(ConnectionState.LOGIN)));
            assertTrue(refused.closedWithoutAByte(), "and nothing after it, no encryption request");
            assertEquals(1, rig.gate().rejected(ConnectionGate.Reason.FULL));
            assertEquals(2, rig.gate().seats());

            first.close();
            awaitUntil(() -> rig.gate().seats() == 1, "a connection closing gives its seat back");
            assertInstanceOf(EncryptionRequestPacket.class, rig.connect(Intent.TRANSFER, NAME).read(ConnectionState.LOGIN),
                    "with a seat free the same transfer is asked for its key first");
        }
    }

    /**
     * The offline login: a full channel answers the transfer before the cookie request, the ticket's
     * nonce stays unspent and the ledger has no event of the connection.
     */
    @Test
    void aFullChannelRefusesATransferBeforeItsCookieIsRead() throws Exception {
        final TransferTicket held = ticket();
        final TransferTicket later = ticket();
        final List<String> lines;
        try (Rig rig = new Rig(new Auth.Offline(false))) {
            final Peer first = rig.connect(Intent.LOGIN, "holder0");
            assertInstanceOf(LoginSuccessPacket.class, first.read(ConnectionState.LOGIN), "the first seat");
            assertInstanceOf(LoginSuccessPacket.class, rig.connect(Intent.LOGIN, "holder1").read(ConnectionState.LOGIN),
                    "the second seat");

            final Peer refused = rig.connect(Intent.TRANSFER, NAME);
            assertEquals("synaxis:shard-full", disconnectKey(refused.read(ConnectionState.LOGIN)));
            assertTrue(refused.closedWithoutAByte(), "and nothing after it, no cookie request");
            assertEquals(1, rig.gate().rejected(ConnectionGate.Reason.FULL));
            assertEquals(2, rig.gate().seats());

            first.close();
            awaitUntil(() -> rig.gate().seats() == 1, "a connection closing gives its seat back");
            final Peer admitted = rig.connect(Intent.TRANSFER, NAME);
            assertInstanceOf(LoginSuccessPacket.class, admitted.read(ConnectionState.LOGIN));
            admitted.send(ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket());
            final CookieRequestPacket request = admitted.readUntil(CookieRequestPacket.class, ConnectionState.CONFIGURATION);
            assertEquals(TransferTicket.COOKIE, request.key(), "with a seat free the ticket is asked for");
            admitted.send(ConnectionState.CONFIGURATION, new ClientCookieResponsePacket(TransferTicket.COOKIE,
                    later.encode(KEYS)));
            awaitUntil(() -> spent(later), "and its nonce is spent");
            assertFalse(spent(held), "the nonce of the ticket the full channel turned away is not");
            lines = rig.closeAndReadLedger();
        }
        assertEquals(1, lines.stream().filter(line -> line.contains("\"session_entered_configuration\"")).count(),
                "only the admitted connection entered the configuration: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains(held.reference())),
                "no event names the ticket that was turned away: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains(later.reference())),
                "the admitted one's ticket went through the verifier, which names it: " + lines);
    }

    private static TransferTicket ticket() {
        final byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        final long now = System.currentTimeMillis();
        return new TransferTicket(7, GameProfile.offlineUuid(NAME), 1, "r0-c1", SHARD, 3, nonce, now,
                now + TransferTicket.LIFETIME_MS);
    }

    private boolean spent(TransferTicket ticket) {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement("SELECT count(*) FROM transfer_nonce WHERE nonce = ?")) {
            select.setBytes(1, ticket.nonce());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1) == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String disconnectKey(@Nullable ServerPacket packet) {
        final LoginDisconnectPacket disconnect = assertInstanceOf(LoginDisconnectPacket.class, packet,
                "expected a login disconnect");
        return assertInstanceOf(TranslatableComponent.class, disconnect.kickMessage()).key();
    }

    private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), what);
    }

    /** A process of two seats that accepts transfers, with the host of a shard around it. */
    private final class Rig implements AutoCloseable {
        private final ServerProcess process;
        private final EventLedger ledger;
        private final PluginHost host;
        private final List<Peer> peers = new ArrayList<>();
        private boolean closed;

        Rig(Auth auth) throws IOException {
            // The gate's L1 and L2 are not under test: one address opens every connection
            process = ServerProcess.of(auth, ServerConfig.builder().insideTest(true).maxPlayers(2)
                    .acceptTransfers(true).compressionThreshold(0).handshakeTimeout(60_000)
                    .connectionGateTrustedNetworks("127.0.0.0/8").build());
            ledger = new EventLedger(workingDirectory.resolve("logs"), SHARD, EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
            ledger.start();
            final PlayerStore store = new PlayerStore(process, new MemoryBackend(), RedisLink.disabled(SHARD), SHARD,
                    false, PlayerStore.Options.DEFAULT, ledger::record);
            final TicketVerifier verifier = new TicketVerifier(pool.pool(), () -> 1L, SHARD, KEYS,
                    new TransferCooldowns(5_000, System::currentTimeMillis), ledger::record, (_, _) -> {
            });
            host = new PluginHost(process, workingDirectory.resolve("plugins"), ledger, store, verifier,
                    HostPermissions.withoutDatabase(process));
            host.enable(List.of(new SynaxisPlugin() {
                @Override
                public PluginDescriptor descriptor() {
                    return new PluginDescriptor("probe", "1.0.0", PluginApi.API_VERSION);
                }

                @Override
                public void onEnable(PluginContext ctx) {
                    // The provider is never asked here: every transfer stops at the gate or the ticket
                    ctx.admission().register(_ -> new Admission.Reject(DisconnectKeys.MATCH_UNAVAILABLE));
                }

                @Override
                public void onDisable() {
                }
            }), Set.of());
            process.server().init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            process.server().start();
        }

        ConnectionGate gate() {
            return process.connection().gate();
        }

        /** A new connection that sends a handshake with the intent and a login start. */
        Peer connect(Intent intent, String name) throws IOException {
            final SocketChannel channel = SocketChannel.open(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()));
            channel.socket().setSoTimeout(10_000);
            final Peer peer = new Peer(channel, process);
            peers.add(peer);
            peer.send(ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), intent));
            peer.send(ConnectionState.LOGIN, new ClientLoginStartPacket(name, UUID.randomUUID()));
            return peer;
        }

        /** Stops the process and the ledger and returns every line the ledger wrote. */
        List<String> closeAndReadLedger() throws IOException {
            close();
            final List<String> lines = new ArrayList<>();
            try (var files = Files.list(workingDirectory.resolve("logs"))) {
                for (Path file : files.toList()) lines.addAll(Files.readAllLines(file));
            }
            return lines;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            for (Peer peer : peers) peer.close();
            host.disableAll();
            process.stop();
            ledger.close();
        }
    }

    /** The client side of one connection, reading with a timeout through the socket adaptor. */
    private record Peer(SocketChannel channel, ServerProcess process) {

        void send(ConnectionState state, ClientPacket packet) throws IOException {
            final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
            while (!framed.writeChannel(channel)) {
                // A blocking channel writes everything, the loop only documents the contract
            }
        }

        /** The next packet the server sends, or null if it closed the connection first. */
        @Nullable ServerPacket read(ConnectionState state) throws IOException {
            final InputStream in = channel.socket().getInputStream();
            final ByteArrayOutputStream frame = new ByteArrayOutputStream();
            int length = 0;
            for (int shift = 0; ; shift += 7) {
                final int b = readByte(in);
                if (b < 0) return null;
                frame.write(b);
                length |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
            }
            for (int i = 0; i < length; i++) {
                final int b = readByte(in);
                if (b < 0) return null;
                frame.write(b);
            }
            final byte[] bytes = frame.toByteArray();
            final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries());
            final PacketReading.Result<ServerPacket> result;
            try {
                result = PacketReading.readServers(buffer, state, false);
            } catch (DataFormatException e) {
                throw new AssertionError("not a packet: " + bytes.length + " bytes", e);
            }
            if (!(result instanceof PacketReading.Result.Success<ServerPacket> success))
                throw new AssertionError("not a packet: " + bytes.length + " bytes");
            return success.packets().getFirst().packet();
        }

        /** Reads past the packets that come before the first one of the type. */
        <T extends ServerPacket> T readUntil(Class<T> type, ConnectionState state) throws IOException {
            for (int i = 0; i < 16; i++) {
                final ServerPacket packet = read(state);
                if (packet == null) throw new AssertionError("closed before a " + type.getSimpleName());
                if (type.isInstance(packet)) return type.cast(packet);
            }
            throw new AssertionError("no " + type.getSimpleName() + " in the first 16 packets");
        }

        boolean closedWithoutAByte() throws IOException {
            return readByte(channel.socket().getInputStream()) < 0;
        }

        private static int readByte(InputStream in) throws IOException {
            try {
                return in.read();
            } catch (SocketTimeoutException e) {
                throw new AssertionError("the server neither answered nor closed the connection", e);
            } catch (IOException _) {
                return -1; // Reset by the server, as good as closed
            }
        }

        void close() {
            try {
                channel.close();
            } catch (IOException _) {
                // Already gone
            }
        }
    }
}
