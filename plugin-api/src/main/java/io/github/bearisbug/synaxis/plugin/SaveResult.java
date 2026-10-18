package io.github.bearisbug.synaxis.plugin;

/**
 * The outcome of a write of player data ({@code docs/DESIGN-PROD.md} API-PROD-005).
 */
public enum SaveResult {
    /** The data is in the database. */
    SAVED,
    /**
     * This shard no longer holds the right to write the character: another shard took it over.
     * Nothing was written, and the host kicks the player.
     */
    FENCED,
    /**
     * The database could not be reached, or the player is frozen (see
     * {@link PlayerDataStore#isFrozen}). Nothing was written. The data of a
     * {@link PlayerDataStore#saveNow} or an autosave is retried later; the staged values of a
     * {@link PlayerDataStore#commitTogether} are dropped.
     */
    UNAVAILABLE
}
