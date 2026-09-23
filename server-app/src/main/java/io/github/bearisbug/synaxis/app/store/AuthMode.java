package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.Auth;

import java.util.Locale;

/**
 * The kind of identity a player store holds, {@code store_meta.auth_mode}
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-036}): Mojang's uuids, or names nobody verified.
 * <p>
 * The two never share a database, and the shard is the one that would mix them up: a process whose
 * effective mode differs from the database's refuses to start.
 */
public enum AuthMode {
    ONLINE,
    OFFLINE;

    /**
     * The mode a process effectively runs in: online only when it verifies against Mojang itself.
     * A process verifying against any other session server lets in whoever that server vouches for,
     * which is what CI and load tests want, and counts as offline so their identities stay out of a
     * production database.
     *
     * @param auth the auth of the process
     * @return the effective mode
     */
    public static AuthMode effective(Auth auth) {
        return auth instanceof Auth.Online online && online.sessionServer().isMojang() ? ONLINE : OFFLINE;
    }

    /**
     * Parses the value of {@code --auth-mode} or of the {@code store_meta} row.
     *
     * @param value {@code online} or {@code offline}
     * @return the mode
     * @throws IllegalArgumentException for anything else
     */
    public static AuthMode parse(String value) {
        return switch (value.strip()) {
            case "online" -> ONLINE;
            case "offline" -> OFFLINE;
            default -> throw new IllegalArgumentException("auth mode " + value + " is neither online nor offline");
        };
    }

    /**
     * The value stored in {@code store_meta}.
     *
     * @return {@code online} or {@code offline}
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
