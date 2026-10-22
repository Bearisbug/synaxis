package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.SessionServer;
import io.github.bearisbug.synaxis.app.store.StoreSettings;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.flywaydb.core.Flyway;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start self-check of a shard against its database ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-036}, {@code API-PROD-001}, {@code API-PROD-004}): {@code store_meta.auth_mode}
 * is written by {@code --migrate-only} alone, and a shard only starts on a store that holds the
 * identities it lets in.
 */
@EnvTest
class MainStoreIntegrationTest {

    @TempDir
    Path workingDirectory;

    private TestDatabase database;
    private @Nullable EventLedger ledger;
    private static final KeyPair KEYS = keys();

    @BeforeEach
    void create() {
        database = TestDatabase.create();
    }

    @AfterEach
    void drop() {
        if (ledger != null) ledger.close();
        if (database != null) database.close();
    }

    private Map<String, String> environment() {
        final StoreSettings settings = database.settings();
        return Map.of("SYNAXIS_STATE_DB_URL", settings.jdbcUrl(), "SYNAXIS_STATE_DB_USER", settings.user(),
                "SYNAXIS_STATE_DB_PASSWORD", settings.password());
    }

    private Main.StoreRuntime open(Env env, Auth auth) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        return Main.openStore(Main.STORE_POSTGRES, env.process(), auth, "r1-c1", false, ledger, environment());
    }

    private static Auth.Online mojang() {
        return new Auth.Online(KEYS, SessionServer.mojang());
    }

    private static Auth.Online fake() {
        return new Auth.Online(KEYS, SessionServer.at(URI.create("http://127.0.0.1:1/session/minecraft/hasJoined")));
    }

    @Test
    void anUnmigratedStoreRefusesEveryShardUntilTheReleaseJobWritesTheMode(Env env) throws SQLException {
        // Built by Flyway, but not by --migrate-only: there is no auth_mode row
        Flyway.configure().dataSource(database.settings().jdbcUrl(), database.settings().user(),
                database.settings().password()).locations("classpath:db/migration").load().migrate();

        final IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> open(env, new Auth.Offline()));
        assertTrue(empty.getMessage().contains("store_meta.auth_mode is not set"), empty.getMessage());
        assertNull(authMode(), "the shard wrote nothing");

        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=online"}, environment()));
        assertEquals("online", authMode());

        final IllegalArgumentException offline = assertThrows(IllegalArgumentException.class,
                () -> open(env, new Auth.Offline()));
        assertTrue(offline.getMessage().contains("store_meta.auth_mode is online but this process runs offline"),
                offline.getMessage());
        open(env, mojang()).close();

        assertEquals(1, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment()),
                "the release job never switches a store");
        assertEquals("online", authMode());
    }

    @Test
    void anOnlineProcessVerifyingAgainstAnythingButMojangCountsAsOffline(Env env) {
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=online"}, environment()));

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> open(env, fake()));
        assertTrue(refused.getMessage().contains("this process runs offline"), refused.getMessage());
    }

    @Test
    void aLoadTestStoreTakesTheOnlineProcessThatVerifiesAgainstAFakeServer(Env env) {
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment()));

        open(env, fake()).close();
        open(env, new Auth.Offline(true)).close();
    }

    /**
     * {@code API-PROD-004} 起服前提, §11.2 {@code [*] → active}: the store opens in step (4), the shard
     * session only right before the game port is bound, so a second process of a running shard id that
     * refuses to start in between leaves the running shard's session in force.
     */
    @Test
    void openingTheStoreLeavesTheSessionOfTheRunningShardAlone(Env env) throws SQLException {
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment()));
        database.execute("INSERT INTO shard_session (shard_id, started_at, expires_at) "
                + "VALUES ('r1-c1', now(), now() + interval '1 hour')");

        try (Main.StoreRuntime runtime = open(env, new Auth.Offline())) {
            assertEquals(List.of(true), sessions(), "the store is open and shard_session is untouched");

            Main.openSession(env.process(), runtime);
            assertEquals(List.of(false, true), sessions(), "this run's session took over the running one");
        }
    }

    /** Whether each session of {@code r1-c1} is in force, in the order they were opened. */
    private List<Boolean> sessions() throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT expires_at > now() FROM shard_session WHERE shard_id = 'r1-c1' ORDER BY session_id")) {
            final List<Boolean> inForce = new ArrayList<>();
            while (rows.next()) inForce.add(rows.getBoolean(1));
            return inForce;
        }
    }

    /**
     * A whitelist that is on and empty lets nobody in, operators included, and only the console can fill it then: the
     * boot self-check says how many accounts it holds and warns when there are none.
     */
    @Test
    void anEmptyWhitelistThatIsOnIsWarnedAboutAtTheStart(Env env) {
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment()));
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        try (LogCapture logs = LogCapture.of(Main.class)) {
            Main.openStore(Main.STORE_POSTGRES, env.process(), new Auth.Offline(), "r1-c1", true, ledger,
                    environment()).close();
            assertTrue(logs.messagesAt(Level.INFO).contains("Effective whitelist: on accounts=0"),
                    logs.messagesAt(Level.INFO).toString());
            // Redis is not set up here, which is a warning of its own
            final List<String> warnings = logs.messagesAt(Level.WARN).stream()
                    .filter(line -> line.contains("whitelist")).toList();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("every login is refused"), warnings.getFirst());
        }

        database.execute("INSERT INTO whitelist (account_uuid) VALUES ('" + UUID.randomUUID() + "')");
        try (LogCapture logs = LogCapture.of(Main.class)) {
            Main.openStore(Main.STORE_POSTGRES, env.process(), new Auth.Offline(), "r1-c1", true, ledger,
                    environment()).close();
            assertTrue(logs.messagesAt(Level.INFO).contains("Effective whitelist: on accounts=1"),
                    logs.messagesAt(Level.INFO).toString());
            assertEquals(List.of(), logs.messagesAt(Level.WARN).stream().filter(line -> line.contains("whitelist")).toList());
        }
    }

    @Test
    void theMigrationNeedsAModeAndTheShardNeedsItsVariables(Env env) {
        assertEquals(1, Main.migrateOnly(new String[]{"--migrate-only"}, environment()));
        assertEquals(1, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=maybe"}, environment()));

        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        final IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Main.openStore(Main.STORE_POSTGRES, env.process(), new Auth.Offline(), "r1-c1", false, ledger, Map.of()));
        assertTrue(missing.getMessage().contains("SYNAXIS_STATE_DB_URL is not set"), missing.getMessage());
    }

    @Test
    void acceptingTransfersNeedsTheDatabaseAndTheKeysAndTheKeysBringBothSides() {
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment()));
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        final ServerProcess accepting = ServerProcess.of(ServerConfig.builder().insideTest(true).acceptTransfers(true).build());

        final IllegalArgumentException memory = assertThrows(IllegalArgumentException.class,
                () -> Main.openStore(Main.STORE_MEMORY, accepting, new Auth.Offline(), "r1-c1", false, ledger, environment()));
        assertTrue(memory.getMessage().contains("needs the postgres store"), memory.getMessage());
        final IllegalArgumentException keyless = assertThrows(IllegalArgumentException.class,
                () -> Main.openStore(Main.STORE_POSTGRES, accepting, new Auth.Offline(), "r1-c1", false, ledger, environment()));
        assertTrue(keyless.getMessage().contains("SYNAXIS_TRANSFER_HMAC_KEYS"), keyless.getMessage());

        final Map<String, String> withKeys = new HashMap<>(environment());
        withKeys.put("SYNAXIS_TRANSFER_HMAC_KEYS", "1:" + Base64.getEncoder().encodeToString(new byte[32]));
        try (Main.StoreRuntime runtime = Main.openStore(Main.STORE_POSTGRES, accepting, new Auth.Offline(), "r1-c1",
                false, ledger, withKeys)) {
            assertTrue(runtime.issuer() != null && runtime.verifier() != null, "both sides of a transfer");
        }
        withKeys.put("SYNAXIS_TRANSFER_HMAC_KEYS", "1:short");
        assertThrows(IllegalArgumentException.class,
                () -> Main.openStore(Main.STORE_POSTGRES, accepting, new Auth.Offline(), "r1-c1", false, ledger, withKeys));
    }

    @Test
    void theStoreIsMemoryUnlessAskedForPostgres() {
        final String previous = System.getProperty("synaxis.app.store");
        try {
            System.clearProperty("synaxis.app.store");
            assertEquals("memory", Main.storeMode());
            System.setProperty("synaxis.app.store", "postgres");
            assertEquals("postgres", Main.storeMode());
            System.setProperty("synaxis.app.store", "redis");
            assertThrows(IllegalArgumentException.class, Main::storeMode);
        } finally {
            if (previous == null) System.clearProperty("synaxis.app.store");
            else System.setProperty("synaxis.app.store", previous);
        }
    }

    private @Nullable String authMode() throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT value FROM store_meta WHERE key = 'auth_mode'")) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private static KeyPair keys() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
