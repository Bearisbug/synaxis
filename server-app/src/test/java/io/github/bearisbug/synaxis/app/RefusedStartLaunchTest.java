package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import io.github.bearisbug.synaxis.app.store.StoreSettings;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shard that refuses to start takes nothing over from the running process of its shard id, in a
 * JVM of its own ({@code docs/DESIGN-PROD.md} {@code API-PROD-004} 起服前提, §11.2
 * {@code [*] → active}; {@code docs/DESIGN-M2.md} {@code API-M2-010} step (1)): the shard session is
 * opened right before the game port is bound, after every refusal on the way, and the host keys are
 * read before the database is touched. A session opened earlier made the running shard's session
 * expire, which isolates that shard and kicks everybody on it.
 */
public class RefusedStartLaunchTest {

    private static final String SHARD = "r1-c1";

    private TestDatabase database;
    /** The output of the last {@link #refused} JVM. */
    private String lastLog = "";

    @BeforeEach
    void create() {
        database = TestDatabase.create();
        assertEquals(0, Main.migrateOnly(new String[]{"--migrate-only", "--auth-mode=offline"}, environment()));
    }

    @AfterEach
    void drop() {
        if (database != null) database.close();
    }

    private Map<String, String> environment() {
        final StoreSettings settings = database.settings();
        return Map.of("SYNAXIS_STATE_DB_URL", settings.jdbcUrl(), "SYNAXIS_STATE_DB_USER", settings.user(),
                "SYNAXIS_STATE_DB_PASSWORD", settings.password());
    }

    /** A shard of {@value #SHARD} storing in this database, refused before it binds the game port. */
    private int refused(Path directory, String... properties) throws IOException, InterruptedException {
        final List<String> launch = new ArrayList<>(List.of("-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline",
                "-Dsynaxis.app.core-mode=on", "-Dsynaxis.app.store=postgres", "-Dsynaxis.app.shard-id=" + SHARD,
                "-Dsynaxis.app.port=" + freePort()));
        launch.addAll(List.of(properties));
        Files.createDirectories(directory);
        try (Launched main = Launched.start(directory, System.getProperty("java.class.path"), environment(), Main.class,
                launch.toArray(String[]::new))) {
            final int exit = main.awaitExit();
            lastLog = main.log();
            return exit;
        }
    }

    /** Whether each session of {@value #SHARD} is in force, in the order they were opened. */
    private List<Boolean> sessions() throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT expires_at > now() FROM shard_session WHERE shard_id = '"
                     + SHARD + "' ORDER BY session_id")) {
            final List<Boolean> inForce = new ArrayList<>();
            while (rows.next()) inForce.add(rows.getBoolean(1));
            return inForce;
        }
    }

    @Test
    public void aSecondProcessRefusedAtItsMetricsPortLeavesTheRunningSessionInForce(@TempDir Path directory)
            throws Exception {
        // The running process of the shard id, as ShardSession#open leaves it
        database.execute("INSERT INTO shard_session (shard_id, started_at, expires_at) "
                + "VALUES ('" + SHARD + "', now(), now() + interval '1 hour')");
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            assertEquals(1, refused(directory, "-Dsynaxis.app.metrics.bind=127.0.0.1:" + taken.getLocalPort()), lastLog);
            assertTrue(lastLog.contains("Refusing to start: synaxis.app.metrics.bind=127.0.0.1:" + taken.getLocalPort()
                    + " cannot be bound: "), lastLog);
        }
        assertFalse(lastLog.contains("Shard session opened"), lastLog);
        assertEquals(List.of(true), sessions(), "the running session is in force and no other was written");
    }

    @Test
    public void aMisspeltWhitelistOrHandshakePhaseIsRefusedBeforeTheDatabase(@TempDir Path directory) throws Exception {
        assertEquals(1, refused(directory.resolve("whitelist"), "-Dsynaxis.app.metrics.bind=127.0.0.1:0",
                "-Dsynaxis.app.whitelist=yes"), lastLog);
        assertTrue(lastLog.contains("Refusing to start: synaxis.app.whitelist=yes is not on or off"), lastLog);
        assertEquals(List.of(), sessions(), "refused before the store was opened");

        assertEquals(1, refused(directory.resolve("phase"), "-Dsynaxis.app.metrics.bind=127.0.0.1:0",
                "-Dsynaxis.app.handshake-phase=play"), lastLog);
        assertTrue(lastLog.contains("Refusing to start: synaxis.app.handshake-phase=play is not one of configuration, "
                + "login"), lastLog);
        assertEquals(List.of(), sessions(), "refused before the store was opened");
    }
}
