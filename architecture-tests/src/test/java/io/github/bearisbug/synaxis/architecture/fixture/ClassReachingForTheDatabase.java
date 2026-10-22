package io.github.bearisbug.synaxis.architecture.fixture;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Stands in for a class outside the host that writes player data around the host's store: with a
 * {@link Connection} in hand it could skip the lease and version check every write of the store
 * makes. Only ever imported as a synthetic {@code JavaClasses}.
 */
public final class ClassReachingForTheDatabase {

    private ClassReachingForTheDatabase() {
    }

    @SuppressWarnings("unused")
    static void write(Connection connection) throws SQLException {
        connection.createStatement().executeUpdate("UPDATE character SET version = 0");
    }
}
