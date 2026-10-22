package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * The analytics ledger of {@code docs/DESIGN-M2.md} §20, as seen by a plugin.
 * <p>
 * The events of §20 are produced on both sides of the plugin boundary: the host writes the session
 * and plugin ones, a plugin writes its own. They all have to end up in the same file, so the host
 * owns it and the plugin hands its events over through here. The file is
 * {@code logs/events-<shard_id>-YYYYMMDD.jsonl}, {@code <shard_id>} being
 * {@code synaxis.app.shard-id} ({@code local} by default); the files of earlier days are compressed
 * to {@code .jsonl.gz} and kept 30 days ({@code docs/DESIGN-PROD.md} {@code API-PROD-014}).
 * <p>
 * This capability holds no state, so there is nothing to take back when the plugin is disabled.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@FunctionalInterface
@ApiStatus.NonExtendable
public interface PluginAnalytics {

    /**
     * Appends one event to the ledger, one JSON object per line. The name and the keys are JSON strings;
     * a value is written as {@code null} when it is null or a number that is not finite, as {@code true}
     * or {@code false} when it is a boolean, as a number when it is any other number, and as the JSON
     * string of its {@link String#valueOf} otherwise.
     *
     * @param event      the event name, without the {@code EVT:} prefix the design document uses
     * @param attributes the attributes of that event, written in iteration order
     */
    void record(String event, Map<String, ?> attributes);
}
