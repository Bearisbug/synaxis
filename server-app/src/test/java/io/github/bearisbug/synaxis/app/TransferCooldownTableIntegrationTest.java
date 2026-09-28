package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.StoreSettings;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.Transfers;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One transfer cooldown table per process ({@code docs/DESIGN-PROD.md} {@code API-PROD-021}):
 * {@link Main#openStore} builds it, the ticket side takes it, and the plugin host hands it to every
 * plugin's {@code Transfers}; a process without transfer keys still has one.
 */
@EnvTest
class TransferCooldownTableIntegrationTest {

    private static final String COOLDOWN_PROPERTY = "synaxis.app.transfer-cooldown-ms";

    @TempDir
    Path workingDirectory;

    private @Nullable TestDatabase database;
    private @Nullable EventLedger ledger;

    @AfterEach
    void close() {
        if (ledger != null) ledger.close();
        if (database != null) database.close();
    }

    private EventLedger ledger() {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        return ledger;
    }

    @Test
    void aPluginTransferCoolsTheTicketSideDown(Env env) {
        database = TestDatabase.create();
        final StoreSettings settings = database.settings();
        final Map<String, String> environment = Map.of("SYNAXIS_STATE_DB_URL", settings.jdbcUrl(),
                "SYNAXIS_STATE_DB_USER", settings.user(), "SYNAXIS_STATE_DB_PASSWORD", settings.password(),
                "SYNAXIS_TRANSFER_HMAC_KEYS", "1:" + Base64.getEncoder().encodeToString(new byte[32]));
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment));

        try (Main.StoreRuntime runtime = Main.openStore(Main.STORE_POSTGRES, env.process(), new Auth.Offline(),
                "r1-c1", ledger(), environment)) {
            final Transfers transfers = transfersOf(new PluginHost(env.process(), workingDirectory.resolve("plugins"),
                    ledger, runtime.store(), runtime.verifier(), runtime.permissions(), runtime.moderation(),
                    runtime.cooldowns()));
            final RecordingConnection connection = new RecordingConnection(env.process());
            final Player player = new Player(connection, new GameProfile(UUID.randomUUID(), "probe"));

            assertTrue(transfers.transfer(player, "lobby.example", 25565));
            assertEquals(List.of(new TransferPacket("lobby.example", 25565)), connection.sent(TransferPacket.class));
            assertEquals(TransferOutcome.COOLDOWN, Objects.requireNonNull(runtime.issuer())
                    .transfer(player, "r1-c2", "127.0.0.1", 25566).orTimeout(5, TimeUnit.SECONDS).join());
            assertEquals(1, connection.sent(TransferPacket.class).size(), "the refused ticket sends nothing");
        }
    }

    @Test
    void aProcessWithoutTransferKeysHasATableAndReadsItsKey(Env env) {
        try (Main.StoreRuntime runtime = Main.openStore(Main.STORE_MEMORY, env.process(), new Auth.Offline(),
                "r1-c1", ledger(), Map.of())) {
            assertNull(runtime.issuer());
            assertNotNull(runtime.cooldowns());
        }

        final String previous = System.getProperty(COOLDOWN_PROPERTY);
        try {
            System.setProperty(COOLDOWN_PROPERTY, "soon");
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> Main.openStore(Main.STORE_MEMORY, env.process(), new Auth.Offline(), "r1-c1", ledger,
                            Map.of()));
            assertTrue(refused.getMessage().contains(COOLDOWN_PROPERTY + "=soon"), refused.getMessage());
        } finally {
            if (previous == null) System.clearProperty(COOLDOWN_PROPERTY);
            else System.setProperty(COOLDOWN_PROPERTY, previous);
        }
    }

    private static Transfers transfersOf(PluginHost host) {
        final AtomicReference<Transfers> transfers = new AtomicReference<>();
        host.enable(List.of(new SynaxisPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("probe", "1.0.0", PluginApi.API_VERSION);
            }

            @Override
            public void onEnable(PluginContext context) {
                transfers.set(context.transfers());
            }
        }), Set.of());
        return Objects.requireNonNull(transfers.get(), "the probe plugin was enabled");
    }

    /** A connection in the play state that keeps what the server sends it. */
    private static final class RecordingConnection extends PlayerConnection {

        private final List<Object> sent = new CopyOnWriteArrayList<>();

        RecordingConnection(ServerProcess process) {
            super(process);
            setServerState(ConnectionState.PLAY);
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
