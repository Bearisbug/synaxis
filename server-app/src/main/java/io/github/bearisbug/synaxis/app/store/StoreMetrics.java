package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.plugin.SaveResult;

/**
 * What the player store tells the metrics endpoint ({@code docs/DESIGN-PROD.md} {@code API-PROD-015}):
 * how long its statements took ({@code synaxis_db_statement_seconds}) and how its saves ended
 * ({@code synaxis_store_save_total}). Called on the thread that ran the statement, never a tick
 * thread.
 */
public interface StoreMetrics {

    /** Tells nobody, the store's default. */
    StoreMetrics NONE = new StoreMetrics() {
        @Override
        public void statement(Statement statement, long nanos) {
        }

        @Override
        public void saved(SaveResult result) {
        }
    };

    /** The statements timed, the {@code op} label. */
    enum Statement {
        /** The load of a character at login. */
        LOAD,
        /** The write of one character: an autosave, a logout, {@code saveNow} or a transfer. */
        SAVE,
        /** The renewal of the shard session. */
        RENEW,
        /** The write of a {@code commitTogether}. */
        COMMIT
    }

    /**
     * One statement ended.
     *
     * @param statement which statement
     * @param nanos     how long it took, a failed one included
     */
    void statement(Statement statement, long nanos);

    /**
     * One write attempt of a character ended, or one {@code commitTogether} that reached the database.
     * Every attempt counts, a retry too: a snapshot with nothing in it is {@code saved} without the
     * database being asked, and a write while the shard session is isolated is {@code fenced} without
     * it either.
     *
     * @param result the outcome
     */
    void saved(SaveResult result);
}
