package io.github.bearisbug.synaxis.app.store;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Where the player store lives ({@code docs/DESIGN-PROD.md} {@code API-PROD-004},
 * {@code API-PROD-006}). All of it is secret, so it comes from the environment and never from a
 * system property, which a crash report would print.
 *
 * @param jdbcUrl  {@code SYNAXIS_STATE_DB_URL}, {@code jdbc:postgresql://host:5432/synaxis}
 * @param user     {@code SYNAXIS_STATE_DB_USER}
 * @param password {@code SYNAXIS_STATE_DB_PASSWORD}
 * @param redisUrl {@code SYNAXIS_REDIS_URL}, {@code redis://host:6379}; null runs without Redis,
 *                 which is what a shard does anyway while Redis is down
 */
public record StoreSettings(String jdbcUrl, String user, String password, @Nullable String redisUrl) {

    static final String DB_URL = "SYNAXIS_STATE_DB_URL";
    static final String DB_USER = "SYNAXIS_STATE_DB_USER";
    static final String DB_PASSWORD = "SYNAXIS_STATE_DB_PASSWORD";
    static final String REDIS_URL = "SYNAXIS_REDIS_URL";

    /**
     * Reads the settings from the environment.
     *
     * @param environment the environment of the process
     * @return the settings
     * @throws IllegalArgumentException if a database variable is missing or the URL is not a
     *                                  PostgreSQL JDBC URL
     */
    public static StoreSettings fromEnvironment(Map<String, String> environment) {
        final String url = required(environment, DB_URL);
        if (!url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException(DB_URL + " is not a jdbc:postgresql:// URL");
        final String redis = environment.get(REDIS_URL);
        return new StoreSettings(url, required(environment, DB_USER), required(environment, DB_PASSWORD),
                redis == null || redis.isBlank() ? null : redis.strip());
    }

    private static String required(Map<String, String> environment, String name) {
        final String value = environment.get(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is not set; the player store needs it (see .env.example)");
        return value.strip();
    }

    @Override
    public String toString() {
        // The password never goes into a log line or a crash report
        return "StoreSettings[jdbcUrl=" + hostsAndDatabase(jdbcUrl) + ", user=" + user + ", password=<set>, redis="
                + (redisUrl == null ? "off" : "<set>") + "]";
    }

    /**
     * The JDBC URL down to its hosts, ports and database: a parameter ({@code sslpassword=},
     * {@code password=}) or a user info carries a secret as much as {@value #DB_PASSWORD} does.
     */
    private static String hostsAndDatabase(String jdbcUrl) {
        final String location = jdbcUrl.split("[?#]", 2)[0];
        final int hosts = location.indexOf("//") + 2;
        // The last '@', not the first one before a '/': a password may hold a '/' as well
        final int userInfo = location.lastIndexOf('@');
        return userInfo < hosts ? location : location.substring(0, hosts) + location.substring(userInfo + 1);
    }
}
