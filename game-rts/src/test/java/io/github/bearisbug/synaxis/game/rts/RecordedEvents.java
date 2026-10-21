package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The analytics ledger of a test: the host writes the real one to
 * {@code logs/events-<shard_id>-YYYYMMDD.jsonl}, here the events stay in memory.
 */
final class RecordedEvents implements PluginAnalytics {

    private final List<Map.Entry<String, Map<String, ?>>> events = new ArrayList<>();

    @Override
    public void record(String event, Map<String, ?> attributes) {
        events.add(Map.entry(event, new LinkedHashMap<>(attributes)));
    }

    List<String> names() {
        return events.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * The attributes of the last event with that name.
     *
     * @param event the event name
     * @return its attributes, null when no such event was recorded
     */
    @Nullable Map<String, ?> last(String event) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).getKey().equals(event)) return events.get(i).getValue();
        }
        return null;
    }
}
