package io.github.bearisbug.synaxis.app.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The transaction every statement group of the store runs in ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-004}).
 */
final class Sql {

    private Sql() {
    }

    /**
     * What runs inside one transaction.
     *
     * @param <T> what it returns
     * @param <X> the checked exception it throws besides {@link SQLException}
     */
    @FunctionalInterface
    interface Work<T, X extends Exception> {
        T run(Connection connection) throws SQLException, X;
    }

    /**
     * Runs work in one transaction on a connection of its own: committed once it returns, rolled back
     * whatever it throws. A rollback that fails too, as it does on a connection the driver already
     * closed, is attached to what the work threw instead of replacing it. Auto-commit is not set back:
     * the pool does that when the connection comes back, and setting it back here would commit what a
     * failed rollback left open, or throw over the work's exception on a closed connection.
     *
     * @param dataSource where the connection comes from
     * @param work       the statements
     * @param <T>        what the work returns
     * @param <X>        the checked exception the work throws besides {@link SQLException}
     * @return what the work returned
     * @throws SQLException if a statement, the commit or getting the connection failed
     * @throws X            if the work threw it
     */
    static <T, X extends Exception> T inTransaction(DataSource dataSource, Work<T, X> work) throws SQLException, X {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                final T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Throwable thrown) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    thrown.addSuppressed(rollback);
                }
                throw thrown;
            }
        }
    }
}
