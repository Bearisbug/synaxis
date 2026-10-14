package io.github.bearisbug.synaxis.app.shard;

import org.jetbrains.annotations.Nullable;

/**
 * How a {@link WorldFlags#set} went ({@code docs/DESIGN-M3.md} {@code API-M3-013}).
 */
public sealed interface FlagWrite {

    /**
     * Written.
     *
     * @param version the version it was written as
     */
    record Written(long version) implements FlagWrite {
    }

    /**
     * The key was not at {@code expectedVersion}: someone wrote it in between, this channel included, or
     * a version was given for a key that is not there.
     *
     * @param current the value and version in the database, null if the key is not there
     */
    record Conflict(@Nullable FlagValue current) implements FlagWrite {
    }

    /**
     * The database failed, or one of its statements took more than 2 s: a write runs up to two of them
     * after waiting for a connection, so this can come later than 2 s after the call. A key that is not
     * {@code <namespace>:<name>}, or a value longer than 4,096 B as PostgreSQL prints jsonb (a space after
     * each {@code :} and {@code ,}), fails the same way, and trying again does not help.
     *
     * @param cause what went wrong
     */
    record Failed(String cause) implements FlagWrite {
    }
}
