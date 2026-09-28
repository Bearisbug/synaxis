package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.app.store.StoreSettings;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.app.transfer.TransferOutcome;
import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.Transfers;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One transfer cooldown table per process ({@code docs/DESIGN-PROD.md} {@code API-PROD-021}):
 * {@link Main#openStore} builds it, both sides of a ticket take it, and the plugin host hands it to
 * every plugin's {@code Transfers}; a process without transfer keys still has one.
 * <p>
 * The player spawns before the plugin host is built: the host's admission would turn it away, the
 * probe plugin registers no admission.
 */
@EnvTest
class TransferCooldownTableIntegrationTest {

    private static final String COOLDOWN_PROPERTY = "synaxis.app.transfer-cooldown-ms";
    private static final Duration DECIDED = Duration.ofSeconds(2);

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
        final Map<String, String> environment = migratedWithKeys();

        try (Main.StoreRuntime runtime = Main.openStore(Main.STORE_POSTGRES, env.process(), new Auth.Offline(),
                "r1-c1", ledger(), environment)) {
            final TestConnection connection = env.createConnection();
            final Player player = spawn(env, connection);
            final Transfers transfers = transfersOf(host(env, runtime));
            final Collector<TransferPacket> sent = connection.trackIncoming(TransferPacket.class);

            assertTrue(decided(env, transfers.transfer(player, "lobby.example", 25565)));
            assertEquals(TransferOutcome.COOLDOWN, Objects.requireNonNull(runtime.issuer())
                    .transfer(player, "r1-c2", "127.0.0.1", 25566).orTimeout(5, TimeUnit.SECONDS).join());
            sent.assertSingle(packet -> assertEquals(new TransferPacket("lobby.example", 25565), packet));
        }
    }

    @Test
    void aTicketTheVerifierTakesCoolsThePluginAndTheIssuerDown(Env env) throws SQLException {
        final Map<String, String> environment = migratedWithKeys();

        try (Main.StoreRuntime runtime = Main.openStore(Main.STORE_POSTGRES, env.process(), new Auth.Offline(),
                "r1-c1", ledger(), environment)) {
            final TestConnection connection = env.createConnection();
            final Player player = spawn(env, connection);
            final Transfers transfers = transfersOf(host(env, runtime));
            final Collector<TransferPacket> sent = connection.trackIncoming(TransferPacket.class);
            // A ticket another shard issued for this account just now, for a character it let go of at version 0
            final long now = System.currentTimeMillis();
            final TransferTicket ticket = new TransferTicket(1, player.getUuid(), character(player.getUuid()), "r1-c0",
                    "r1-c1", 0, new byte[16], now, now + TransferTicket.LIFETIME_MS);

            assertTrue(Objects.requireNonNull(runtime.verifier()).claim(player, ticket));
            assertFalse(decided(env, transfers.transfer(player, "lobby.example", 25565)),
                    "the verifier recorded the arrival in the table the plugins read");
            assertEquals(TransferOutcome.COOLDOWN, Objects.requireNonNull(runtime.issuer())
                    .transfer(player, "r1-c2", "127.0.0.1", 25566).orTimeout(5, TimeUnit.SECONDS).join());
            sent.assertEmpty();
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

    /** A migrated database and the environment of a process with transfer keys. */
    private Map<String, String> migratedWithKeys() {
        database = TestDatabase.create();
        final StoreSettings settings = database.settings();
        final Map<String, String> environment = Map.of("SYNAXIS_STATE_DB_URL", settings.jdbcUrl(),
                "SYNAXIS_STATE_DB_USER", settings.user(), "SYNAXIS_STATE_DB_PASSWORD", settings.password(),
                "SYNAXIS_TRANSFER_HMAC_KEYS", "1:" + Base64.getEncoder().encodeToString(new byte[32]));
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment));
        return environment;
    }

    /** The plugin host as the process assembly builds it, on the cooldown table of the runtime. */
    private PluginHost host(Env env, Main.StoreRuntime runtime) {
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), Objects.requireNonNull(ledger),
                runtime.store(), runtime.verifier(), runtime.permissions(), runtime.moderation(), runtime.cooldowns());
    }

    /** A character of the account with no lease, at version 0. */
    private long character(UUID account) throws SQLException {
        try (Connection connection = Objects.requireNonNull(database).connect()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO account (account_uuid, last_name) VALUES (?, 'probe')")) {
                insert.setObject(1, account);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO character (account_uuid, slot) VALUES (?, 0) RETURNING character_id")) {
                insert.setObject(1, account);
                try (ResultSet row = insert.executeQuery()) {
                    assertTrue(row.next());
                    return row.getLong(1);
                }
            }
        }
    }

    /** A player in the world, owned by a tick thread: one tick has run since it spawned. */
    private static Player spawn(Env env, TestConnection connection) {
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 0));
        env.tick();
        return player;
    }

    /** Ticks until the player's tick thread has decided the transfer. */
    private static boolean decided(Env env, CompletableFuture<Boolean> transfer) {
        assertTrue(env.tickWhile(() -> !transfer.isDone(), DECIDED), "decided by the player's tick");
        return transfer.join();
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
}
