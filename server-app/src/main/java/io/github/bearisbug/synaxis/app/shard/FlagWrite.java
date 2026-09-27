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
     * Another channel wrote first.
     *
     * @param current the value and version in the database, null if the key is not there
     */
    record Conflict(@Nullable FlagValue current) implements FlagWrite {
    }

    /**
     * The database failed or took more than 2 s.
     *
     * @param cause what went wrong
     */
    record Failed(String cause) implements FlagWrite {
    }
}
