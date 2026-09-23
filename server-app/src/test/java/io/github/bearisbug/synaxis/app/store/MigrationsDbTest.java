package io.github.bearisbug.synaxis.app.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema of the player store ({@code docs/DESIGN-PROD.md} §25, §24 database checks) and the
 * {@code store_meta.auth_mode} row only the release job writes ({@code REQ-PROD-036}).
 */
class MigrationsDbTest {

    private TestDatabase database;
    private Database pool;

    @BeforeEach
    void create() {
        database = TestDatabase.create();
        pool = new Database(database.settings());
    }

    @AfterEach
    void drop() {
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    @Test
    void anEmptyDatabaseMigratesAndThenValidates() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> Migrations.validate(pool.pool()),
                "a database nobody migrated is refused");

        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);

        Migrations.validate(pool.pool());
        assertEquals(List.of("1", "2", "3", "4"), appliedVersions());
        assertEquals(List.of("account", "account_role", "ban", "character", "character_location", "character_section",
                "flyway_schema_history", "item_ledger", "role", "role_permission", "shard_session", "store_meta",
                "transfer_nonce", "whitelist"), tables());
    }

    @Test
    void theDatabaseOfThePreviousVersionMigratesForward() throws SQLException {
        Flyway.configure().dataSource(pool.pool()).locations("classpath:db/migration").target("1").load().migrate();
        assertEquals(List.of("1"), appliedVersions());
        assertThrows(IllegalArgumentException.class, () -> Migrations.validate(pool.pool()),
                "V2 to V4 are pending, the shard refuses");

        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);

        assertEquals(List.of("1", "2", "3", "4"), appliedVersions());
        Migrations.validate(pool.pool());
    }

    @Test
    void aDatabaseAheadOfThisBuildIsRefused() throws SQLException {
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        database.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, "
                + "checksum, installed_by, execution_time, success) VALUES (99, '99', 'from a newer build', 'SQL', "
                + "'V99__newer.sql', 0, 'test', 0, true)");

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Migrations.validate(pool.pool()));
        assertTrue(refused.getMessage().contains("ahead of this build: V99"), refused.getMessage());
    }

    @Test
    void everyVersionRollsBackAndMigratesAgain() throws Exception {
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);

        database.execute(resource("db/rollback/V4__moderation.down.sql"));
        assertEquals(List.of("1", "2", "3"), appliedVersions());
        assertTrue(!tables().contains("ban") && !tables().contains("whitelist"));
        database.execute(resource("db/rollback/V3__roles.down.sql"));
        assertEquals(List.of("1", "2"), appliedVersions());
        assertTrue(!tables().contains("role") && !tables().contains("role_permission") && !tables().contains("account_role"));
        database.execute(resource("db/rollback/V2__item_ledger.down.sql"));
        assertEquals(List.of("1"), appliedVersions());
        assertTrue(!tables().contains("item_ledger"));
        database.execute(resource("db/rollback/V1__player_store.down.sql"));
        assertEquals(List.of(), appliedVersions());
        assertEquals(List.of("flyway_schema_history"), tables());

        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        assertEquals(List.of("1", "2", "3", "4"), appliedVersions());
        Migrations.validate(pool.pool());
    }

    @Test
    void theAuthModeIsWrittenOnceByTheMigrationAndNeverChanged() throws SQLException {
        Flyway.configure().dataSource(pool.pool()).locations("classpath:db/migration").load().migrate();
        final IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Migrations.requireAuthMode(pool.pool(), AuthMode.OFFLINE));
        assertTrue(missing.getMessage().contains("store_meta.auth_mode is not set"), missing.getMessage());
        assertNull(authMode(), "a shard never writes the row");

        Migrations.migrate(pool.pool(), AuthMode.ONLINE);
        assertEquals("online", authMode());
        Migrations.requireAuthMode(pool.pool(), AuthMode.ONLINE);
        final IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> Migrations.requireAuthMode(pool.pool(), AuthMode.OFFLINE));
        assertTrue(mismatch.getMessage().contains("store_meta.auth_mode is online but this process runs offline"),
                mismatch.getMessage());

        final IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> Migrations.migrate(pool.pool(), AuthMode.OFFLINE));
        assertTrue(changed.getMessage().contains("nothing was migrated"), changed.getMessage());
        assertEquals("online", authMode());
    }

    private List<String> appliedVersions() throws SQLException {
        final List<String> versions = new ArrayList<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (rows.next()) versions.add(rows.getString(1));
        } catch (SQLException e) {
            if ("42P01".equals(e.getSQLState())) return versions;
            throw e;
        }
        return versions;
    }

    private List<String> tables() throws SQLException {
        final List<String> tables = new ArrayList<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")) {
            while (rows.next()) tables.add(rows.getString(1));
        }
        return tables;
    }

    private String authMode() throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT value FROM store_meta WHERE key = 'auth_mode'")) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(MigrationsDbTest.class.getClassLoader().getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
