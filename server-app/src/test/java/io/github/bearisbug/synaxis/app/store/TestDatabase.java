package io.github.bearisbug.synaxis.app.store;

import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * A database of its own for one test class, on the shared PostgreSQL of the machine: the container
 * {@code synaxis-pg} locally, the service container in CI ({@code docs/TEST.md} §3).
 * <p>
 * Where it is comes from {@code SYNAXIS_TEST_PG_URL} (an admin JDBC URL, any database),
 * {@code SYNAXIS_TEST_PG_USER} and {@code SYNAXIS_TEST_PG_PASSWORD}; Redis from
 * {@code SYNAXIS_TEST_REDIS_URL}. Without {@code SYNAXIS_TEST_PG_URL} a machine that has no such
 * database skips these tests; with it set, as in CI, an unreachable database fails them.
 * The database is created under a random name and dropped by {@link #close()}.
 */
public final class TestDatabase implements AutoCloseable {

    private static final String DEFAULT_URL = "jdbc:postgresql://127.0.0.1:5440/postgres";
    private static final String DEFAULT_REDIS = "redis://127.0.0.1:6440";

    private final String adminUrl;
    private final String name;
    private final StoreSettings settings;

    private TestDatabase(String adminUrl, String name, StoreSettings settings) {
        this.adminUrl = adminUrl;
        this.name = name;
        this.settings = settings;
    }

    /**
     * Creates an empty database.
     *
     * @return the database, dropped on close
     */
    public static TestDatabase create() {
        final String configured = System.getenv("SYNAXIS_TEST_PG_URL");
        final String adminUrl = configured == null || configured.isBlank() ? DEFAULT_URL : configured.strip();
        final String name = "w3a_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toLowerCase(Locale.ROOT);
        try (Connection admin = DriverManager.getConnection(adminUrl, credentials());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            Assumptions.assumeTrue(configured != null, "no PostgreSQL for the store tests at " + adminUrl
                    + " and SYNAXIS_TEST_PG_URL is not set: " + e.getMessage());
            throw new IllegalStateException("SYNAXIS_TEST_PG_URL is set and cannot be reached", e);
        }
        final String url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + name;
        return new TestDatabase(adminUrl, name, new StoreSettings(url, user(), password(), redisUrl()));
    }

    /**
     * The URL of the machine's Redis, for tests that need one.
     *
     * @return the Redis URL
     */
    public static String redisUrl() {
        final String configured = System.getenv("SYNAXIS_TEST_REDIS_URL");
        return configured == null || configured.isBlank() ? DEFAULT_REDIS : configured.strip();
    }

    /**
     * The settings pointing at this database.
     *
     * @return the settings
     */
    public StoreSettings settings() {
        return settings;
    }

    /**
     * Runs statements as the owner of this database, for a test that has to put the database in a
     * state no production path writes.
     *
     * @param sql the statements
     */
    public void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(settings.jdbcUrl(), credentials());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /**
     * Opens a connection to this database, for assertions.
     *
     * @return a connection, the caller closes it
     * @throws SQLException if it cannot be opened
     */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(settings.jdbcUrl(), credentials());
    }

    private static Properties credentials() {
        final Properties properties = new Properties();
        properties.setProperty("user", user());
        properties.setProperty("password", password());
        return properties;
    }

    private static String user() {
        final String user = System.getenv("SYNAXIS_TEST_PG_USER");
        return user == null || user.isBlank() ? "postgres" : user.strip();
    }

    private static String password() {
        final String password = System.getenv("SYNAXIS_TEST_PG_PASSWORD");
        return password == null ? "" : password;
    }

    @Override
    public void close() {
        try (Connection admin = DriverManager.getConnection(adminUrl, credentials());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("could not drop " + name, e);
        }
    }
}
