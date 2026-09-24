package io.github.bearisbug.synaxis.app.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The connections of one shard to PostgreSQL ({@code docs/DESIGN-PROD.md} {@code API-PROD-004}):
 * a pool of {@value #POOL_SIZE} for loads and saves, and a connection of its own for the renewal of
 * the shard session, so that a login storm or a shutdown save that takes the whole pool never makes
 * the renewal wait.
 */
public final class Database implements AutoCloseable {

    static final int POOL_SIZE = 4;
    static final int CONNECTION_TIMEOUT_MS = 2_000;
    static final int STATEMENT_TIMEOUT_MS = 2_000;
    /**
     * How long a read on the socket may wait. A database that stops answering without closing the
     * connection (paused, or a network that drops packets) never trips the statement timeout, which
     * the server enforces; without this a statement would wait for it forever.
     */
    static final int SOCKET_TIMEOUT_MS = 4_000;
    static final int VALIDATION_TIMEOUT_MS = 1_000;

    private final StoreSettings settings;
    private final HikariDataSource pool;

    /**
     * Opens the pool, which connects once right away: a database that cannot be reached fails here.
     *
     * @param settings where the database is
     * @throws RuntimeException if the first connection fails
     */
    public Database(StoreSettings settings) {
        this.settings = settings;
        final HikariConfig config = new HikariConfig();
        config.setPoolName("synaxis-store");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(POOL_SIZE);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setValidationTimeout(VALIDATION_TIMEOUT_MS);
        config.setDataSourceProperties(driverProperties());
        this.pool = new HikariDataSource(config);
    }

    /**
     * The pool of loads, saves and commits.
     *
     * @return the pool
     */
    public DataSource pool() {
        return pool;
    }

    /**
     * Returns the connections of the pool in use right now ({@code synaxis_db_pool_active}).
     */
    public int activeConnections() {
        final HikariPoolMXBean bean = pool.getHikariPoolMXBean();
        return bean == null ? 0 : bean.getActiveConnections();
    }

    /**
     * Returns the threads waiting for a connection of the pool right now ({@code synaxis_db_pool_pending}).
     */
    public int pendingThreads() {
        final HikariPoolMXBean bean = pool.getHikariPoolMXBean();
        return bean == null ? 0 : bean.getThreadsAwaitingConnection();
    }

    /**
     * Opens a connection that is not part of the pool, for the renewal of the shard session.
     *
     * @return a new connection, the caller closes it
     * @throws SQLException if the database cannot be reached
     */
    public Connection dedicated() throws SQLException {
        final Properties properties = driverProperties();
        properties.setProperty("user", settings.user());
        properties.setProperty("password", settings.password());
        return DriverManager.getConnection(settings.jdbcUrl(), properties);
    }

    private static Properties driverProperties() {
        final Properties properties = new Properties();
        // Every statement of the store is bounded (API-PROD-004), the server enforces it
        properties.setProperty("options", "-c statement_timeout=" + STATEMENT_TIMEOUT_MS);
        properties.setProperty("connectTimeout", String.valueOf(CONNECTION_TIMEOUT_MS / 1_000));
        // The TCP handshake of a paused database still succeeds, it is the login that waits
        properties.setProperty("loginTimeout", String.valueOf(CONNECTION_TIMEOUT_MS / 1_000));
        properties.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_MS / 1_000));
        properties.setProperty("ApplicationName", "synaxis");
        return properties;
    }

    @Override
    public void close() {
        pool.close();
    }
}
