package io.github.bearisbug.synaxis.app.store;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.ValidateResult;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The schema of the player store and the one row that says whose identities it holds
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-004}, §25, {@code REQ-PROD-036}).
 * <p>
 * Only the release job migrates ({@code server-app --migrate-only --auth-mode=...}), with an account
 * that may change the schema; a shard only checks. That job is also the only writer of
 * {@code store_meta.auth_mode}: a shard that could write it would let the first process to start
 * decide what a production database is.
 */
public final class Migrations {

    private static final String LOCATION = "classpath:db/migration";

    private Migrations() {
    }

    /**
     * {@code --migrate-only}: migrates the schema, then records the auth mode.
     *
     * @param dataSource the database, through an account allowed to change its schema
     * @param mode       what {@code --auth-mode} says
     * @throws IllegalArgumentException if the database already holds the other mode; nothing is
     *                                  migrated then
     * @throws SQLException             if the database cannot be reached
     */
    public static void migrate(DataSource dataSource, AuthMode mode) throws SQLException {
        final String existing = readAuthMode(dataSource);
        if (existing != null && !existing.equals(mode.value()))
            throw new IllegalArgumentException("store_meta.auth_mode is " + existing + " and --auth-mode is "
                    + mode.value() + "; a store never changes whose identities it holds, nothing was migrated");
        flyway(dataSource).migrate();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO store_meta (key, value) VALUES ('auth_mode', ?) ON CONFLICT (key) DO NOTHING")) {
            insert.setString(1, mode.value());
            insert.executeUpdate();
        }
        final String recorded = readAuthMode(dataSource);
        if (!mode.value().equals(recorded))
            throw new IllegalArgumentException("store_meta.auth_mode is " + recorded + " and --auth-mode is " + mode.value());
    }

    /**
     * What a shard does at start: the schema must be exactly what this build expects, neither
     * behind it (a migration was not run) nor ahead of it (a newer build migrated the database).
     *
     * @param dataSource the database
     * @throws IllegalArgumentException if it is not
     */
    public static void validate(DataSource dataSource) {
        final Flyway flyway = flyway(dataSource);
        final ValidateResult result = flyway.validateWithResult();
        if (!result.validationSuccessful)
            throw new IllegalArgumentException("the database schema is not the one this build expects: "
                    + result.getAllErrorMessages() + "; run server-app --migrate-only first");
        final MigrationInfo[] future = Arrays.stream(flyway.info().applied())
                .filter(info -> info.getState().name().startsWith("FUTURE")).toArray(MigrationInfo[]::new);
        if (future.length > 0)
            throw new IllegalArgumentException("the database schema is ahead of this build: "
                    + Arrays.stream(future).map(info -> "V" + info.getVersion()).collect(Collectors.joining(", "))
                    + " unknown here");
    }

    /**
     * The start self-check of a shard: the database must say which identities it holds, and they
     * must be the ones this process lets in.
     *
     * @param dataSource the database
     * @param effective  the effective mode of this process, {@link AuthMode#effective}
     * @throws IllegalArgumentException if the row is missing or says the other mode
     * @throws SQLException             if the database cannot be reached
     */
    public static void requireAuthMode(DataSource dataSource, AuthMode effective) throws SQLException {
        final String recorded = readAuthMode(dataSource);
        if (recorded == null)
            throw new IllegalArgumentException("store_meta.auth_mode is not set: this database was not migrated by "
                    + "the release job; run server-app --migrate-only --auth-mode=online|offline");
        if (!recorded.equals(effective.value()))
            throw new IllegalArgumentException("store_meta.auth_mode is " + recorded + " but this process runs "
                    + effective.value() + "; a shard only serves the identities its database holds");
    }

    private static @Nullable String readAuthMode(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement exists = connection.prepareStatement("SELECT to_regclass('store_meta') IS NOT NULL");
                 ResultSet row = exists.executeQuery()) {
                if (!row.next() || !row.getBoolean(1)) return null;
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT value FROM store_meta WHERE key = 'auth_mode'");
                 ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .cleanDisabled(true)
                .load();
    }
}
