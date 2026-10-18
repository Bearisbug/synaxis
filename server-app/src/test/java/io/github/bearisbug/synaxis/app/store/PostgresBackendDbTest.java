package io.github.bearisbug.synaxis.app.store;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The transactions of the store's statements ({@code docs/DESIGN-PROD.md} {@code API-PROD-004}): a
 * write that fails half way, whatever it fails with, leaves nothing behind, and a rollback that
 * fails as well does not hide why the write failed.
 */
class PostgresBackendDbTest {

    private TestDatabase database;
    private Database pool;
    private ShardSession session;

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        session = new ShardSession(pool::dedicated, "r1-c1", StoreFixture.MANUAL, System::nanoTime, (_, _) -> {
        });
        session.open();
    }

    @AfterEach
    void drop() {
        if (session != null) session.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** What the test makes a call on the pool's connections throw instead; null lets the call through. */
    @FunctionalInterface
    private interface Fault {
        @Nullable Throwable on(String method, @Nullable String sql);
    }

    /** A character this session holds, and the write of one section of it. */
    private StoreBackend.Write aWrite() throws SQLException {
        final StoreBackend.LoadOutcome.Acquired acquired = assertInstanceOf(StoreBackend.LoadOutcome.Acquired.class,
                new PostgresBackend(pool.pool()).load(UUID.randomUUID(), "Halfway", session.sessionId()));
        return new StoreBackend.Write(acquired.characterId(), acquired.version(),
                Map.of("probe:purse", new StoreBackend.StoredSection(2, "{\"coins\": 1}")), false);
    }

    @Test
    void aWriteThatFailsWithAnythingHalfWayIsRolledBack() throws SQLException {
        final StoreBackend.Write write = aWrite();
        final PostgresBackend backend = new PostgresBackend(faulty(pool.pool(), (method, sql) ->
                method.equals("addBatch") && sql != null && sql.contains("character_section")
                        ? new IllegalStateException("the test's driver fails") : null));

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> backend.write(session.sessionId(), List.of(write)));

        assertEquals("the test's driver fails", thrown.getMessage());
        assertEquals(0, count(database, "SELECT version FROM character WHERE character_id = " + write.characterId()),
                "the version bump before it is rolled back with the rest");
        assertEquals(0, count(database, "SELECT count(*) FROM character_section WHERE character_id = "
                + write.characterId()));
    }

    @Test
    void aRollbackThatFailsDoesNotHideWhyTheWriteFailed() throws SQLException {
        final StoreBackend.Write write = aWrite();
        final PostgresBackend backend = new PostgresBackend(faulty(pool.pool(), (method, sql) -> {
            if (method.equals("executeBatch") && sql != null && sql.contains("character_section"))
                return new SQLException("the test's read timed out");
            return method.equals("rollback") ? new SQLException("This connection has been closed.") : null;
        }));

        final SQLException thrown = assertThrows(SQLException.class, () -> backend.write(session.sessionId(), List.of(write)));

        assertEquals("the test's read timed out", thrown.getMessage(), "the failure of the write, not of its rollback");
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("This connection has been closed.", thrown.getSuppressed()[0].getMessage());
        assertEquals(0, count(database, "SELECT version FROM character WHERE character_id = " + write.characterId()),
                "the pool rolls back what the rollback could not");
    }

    private static DataSource faulty(DataSource pool, Fault fault) {
        return (DataSource) Proxy.newProxyInstance(PostgresBackendDbTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (_, method, args) -> {
                    final Object result = invoke(pool, method, args);
                    return method.getName().equals("getConnection") ? connection((Connection) result, fault) : result;
                });
    }

    private static Connection connection(Connection real, Fault fault) {
        return (Connection) Proxy.newProxyInstance(PostgresBackendDbTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (_, method, args) -> {
                    final Throwable thrown = fault.on(method.getName(), null);
                    if (thrown != null) throw thrown;
                    final Object result = invoke(real, method, args);
                    return method.getName().equals("prepareStatement")
                            ? statement((PreparedStatement) result, (String) args[0], fault) : result;
                });
    }

    private static PreparedStatement statement(PreparedStatement real, String sql, Fault fault) {
        return (PreparedStatement) Proxy.newProxyInstance(PostgresBackendDbTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (_, method, args) -> {
                    final Throwable thrown = fault.on(method.getName(), sql);
                    if (thrown != null) throw thrown;
                    return invoke(real, method, args);
                });
    }

    private static @Nullable Object invoke(Object target, Method method, Object @Nullable [] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
