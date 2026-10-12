package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The six {@code play_instance_*} lines of the event ledger ({@code docs/DESIGN-PLAY.md} §20; the
 * meaning of each attribute is in the implementation entry of {@code API-PLAY-006}).
 */
final class InstanceLedger {

    private final PluginAnalytics ledger;

    InstanceLedger(PluginAnalytics ledger) {
        this.ledger = ledger;
    }

    void created(ManagedInstanceImpl managed, int chunkRadius) {
        final Map<String, Object> attributes = instance(managed);
        attributes.put("chunk_radius", chunkRadius);
        ledger.record("play_instance_created", attributes);
    }

    void opened(ManagedInstanceImpl managed, long prepareMs) {
        final Map<String, Object> attributes = instance(managed);
        attributes.put("prepare_ms", prepareMs);
        ledger.record("play_instance_opened", attributes);
    }

    void started(ManagedInstanceImpl managed, int players) {
        final Map<String, Object> attributes = instance(managed);
        attributes.put("players", players);
        ledger.record("play_instance_started", attributes);
    }

    void ended(ManagedInstanceImpl managed, EndCause cause, int players, long durationMs) {
        final Map<String, Object> attributes = instance(managed);
        attributes.put("cause", name(cause));
        attributes.put("players", players);
        attributes.put("duration_ms", durationMs);
        ledger.record("play_instance_ended", attributes);
    }

    /**
     * Writes the line of a transition into {@code CLOSED}, once the instance is unregistered or dropped.
     *
     * @param players       the players online inside when the instance closed
     * @param evacuated     how many of them left before the evacuation ran out
     * @param kicked        how many players were still inside when it ran out, and were kicked
     * @param settleTimeout whether the host closed it because the plugin did not settle in time
     */
    void closed(ManagedInstanceImpl managed, EndCause cause, int players, long durationMs, int evacuated, int kicked,
                boolean settleTimeout) {
        final Map<String, Object> attributes = instance(managed);
        attributes.put("cause", name(cause));
        attributes.put("players", players);
        attributes.put("duration_ms", durationMs);
        attributes.put("evacuated", evacuated);
        attributes.put("kicked", kicked);
        attributes.put("settle_timeout", settleTimeout);
        ledger.record("play_instance_closed", attributes);
    }

    void moved(ManagedInstanceImpl managed, Player player, @Nullable Instance from) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("instance_id", managed.instanceId().toString());
        attributes.put("connection_id", player.getPlayerConnection().connectionId());
        attributes.put("from_instance_id", from == null ? null : from.getUuid().toString());
        ledger.record("play_instance_moved", attributes);
    }

    private static Map<String, Object> instance(ManagedInstanceImpl managed) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind_id", managed.kindId());
        attributes.put("instance_id", managed.instanceId().toString());
        return attributes;
    }

    private static String name(EndCause cause) {
        return cause.name().toLowerCase(Locale.ROOT);
    }
}
