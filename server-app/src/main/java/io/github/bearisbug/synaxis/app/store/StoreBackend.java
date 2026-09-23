package io.github.bearisbug.synaxis.app.store;

import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where the characters of the player store live: PostgreSQL on a shard, memory in a process that
 * has no database ({@code docs/DESIGN-PROD.md} §23 {@code synaxis.app.store}). Every method blocks,
 * none of them runs on a tick thread.
 */
public interface StoreBackend {

    /**
     * Creates the account and its character on the first login, then takes the character's lease
     * for this session ({@code API-PROD-004}).
     *
     * @param account   the account uuid
     * @param name      the name the player logged in with, for display
     * @param sessionId the session of this shard
     * @return the character and its sections, or who holds it
     * @throws SQLException if the database failed
     */
    LoadOutcome load(UUID account, String name, long sessionId) throws SQLException;

    /**
     * Writes one or more characters in one transaction: each write checks, in the statement that
     * bumps the version, that this session holds the lease and that the version is the one the
     * write was based on. One failed check and nothing is written.
     *
     * @param sessionId the session of this shard
     * @param writes    the writes, at most one per character
     * @throws FencedException if a check failed
     * @throws SQLException    if the database failed
     */
    void write(long sessionId, List<Write> writes) throws FencedException, SQLException;

    /**
     * Gives the lease back without writing anything: a load that is not going to be used, or a
     * logout whose save could never be written.
     *
     * @param characterId the character
     * @param sessionId   the session of this shard
     * @throws SQLException if the database failed
     */
    void release(long characterId, long sessionId) throws SQLException;

    /**
     * One stored section.
     *
     * @param schemaVersion the version it was written with
     * @param json          its JSON text
     */
    record StoredSection(int schemaVersion, String json) {
    }

    /**
     * A write of one character.
     *
     * @param characterId     the character
     * @param expectedVersion the version the write is based on; the write makes it one higher
     * @param sections        the sections to write, by section id
     * @param release         whether to give the lease back in the same transaction
     */
    record Write(long characterId, long expectedVersion, Map<String, StoredSection> sections, boolean release) {
    }

    /** What a load found. */
    sealed interface LoadOutcome {

        /**
         * This session holds the lease now.
         *
         * @param characterId     the character
         * @param version         its version
         * @param sections        its stored sections, by section id
         * @param previousSession the session that held the lease before, null if nobody or this one
         * @param lastSavedAt     when it was last saved, null if never
         */
        record Acquired(long characterId, long version, Map<String, StoredSection> sections,
                        @Nullable Long previousSession, @Nullable Instant lastSavedAt) implements LoadOutcome {
        }

        /**
         * A live session of another shard holds the lease.
         *
         * @param shardId   the shard that holds it
         * @param sessionId its session
         */
        record Held(String shardId, long sessionId) implements LoadOutcome {
        }
    }

    /** A write found that this session no longer holds the lease, or that the version moved on. */
    final class FencedException extends Exception {

        private static final long serialVersionUID = 1L;

        private final long characterId;

        public FencedException(long characterId) {
            super("character_id=" + characterId + " is no longer this session's to write", null, false, false);
            this.characterId = characterId;
        }

        public long characterId() {
            return characterId;
        }
    }
}
