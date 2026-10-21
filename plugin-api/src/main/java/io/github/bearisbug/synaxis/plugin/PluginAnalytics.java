package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * The analytics ledger of {@code docs/DESIGN-M2.md} §20, as seen by a plugin.
 * <p>
 * The events of §20 are produced on both sides of the plugin boundary: the host writes the session
 * and plugin ones, the plugin writes the match, unit and order ones. They all have to end up in the
 * same file, so the host owns it and the plugin hands its events over through here. The file is
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
     * Appends one event to the ledger.
     *
     * @param event      the event name, without the {@code EVT:} prefix the design document uses
     * @param attributes the attributes of that event, written in iteration order
     */
    void record(String event, Map<String, ?> attributes);
}
