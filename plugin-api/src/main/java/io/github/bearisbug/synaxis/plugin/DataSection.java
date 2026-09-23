package io.github.bearisbug.synaxis.plugin;

import com.google.gson.JsonElement;

/**
 * One block of player data a plugin defines, such as an inventory or a quest log
 * ({@code docs/DESIGN-PROD.md} API-PROD-005). The host stores it as JSON, marks it dirty and
 * upgrades it one section at a time; it never looks inside.
 *
 * @param <T> the in memory model of the section
 */
public interface DataSection<T> {

    /**
     * The id of the section, {@code <plugin_id>:<name>}. It is the key the section is stored under,
     * so it never changes once players have data in it.
     *
     * @return the section id
     */
    String id();

    /**
     * The format version this code writes. It only goes up; a stored section with a higher version
     * than this refuses the login rather than being decoded by code that does not know it.
     *
     * @return the current format version
     */
    int schemaVersion();

    /**
     * The value of a player who has nothing stored for this section yet.
     *
     * @return a fresh default value
     */
    T createDefault();

    /**
     * Decodes a stored section, upgrading it from the version it was written with.
     *
     * @param json          the stored JSON
     * @param storedVersion the version it was written with, never above {@link #schemaVersion()}
     * @return the decoded value
     * @throws RuntimeException if the JSON cannot be decoded; the host then refuses the login and
     *                          leaves the stored bytes as they are
     */
    T decode(JsonElement json, int storedVersion);

    /**
     * Encodes a value. Called on the tick thread that owns the player; the returned tree must not
     * share mutable state with the value, the host writes it later on another thread.
     *
     * @param value the value
     * @return its JSON form, at most 65,536 bytes once written out
     */
    JsonElement encode(T value);
}
