package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.network.ConnectionState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the bots of one process measured, summed over them. Written by the bots' threads, read once
 * when the run writes its file.
 */
final class BotStats {

    final List<String> playNames = Protocol.serverNames(ConnectionState.PLAY);
    /** Server to bot, per play packet id: count and wire bytes. */
    final AtomicLongArray playCount = new AtomicLongArray(playNames.size());
    final AtomicLongArray playBytes = new AtomicLongArray(playNames.size());
    /** Server to bot, every state, wire bytes. */
    final LongAdder inboundBytes = new LongAdder();
    final LongAdder inboundPackets = new LongAdder();

    final LongAdder chunkPackets = new LongAdder();
    final LongAdder chunkBytes = new LongAdder();
    /** The same, only what arrived after a bot's warm up: the walking figure without the spawn burst. */
    final LongAdder steadyChunkPackets = new LongAdder();
    final LongAdder steadyChunkBytes = new LongAdder();
    final DoubleAdder connectionSeconds = new DoubleAdder();
    final DoubleAdder steadyConnectionSeconds = new DoubleAdder();
    final Histogram chunksPerBot = new Histogram(1);

    final Histogram loginMillis = new Histogram(0.1);
    final Histogram chunkBatchMillis = new Histogram(0.1);
    final LongAdder chunkBatches = new LongAdder();

    /** Bot to server, i.e. the server's inbound packets. */
    final LongAdder outboundPackets = new LongAdder();
    final Map<String, LongAdder> outboundByPacket = new ConcurrentHashMap<>();
    final Histogram outboundPerSecond = new Histogram(1);
    final Histogram outboundPerSevenSeconds = new Histogram(1);
    /** The entities a bot sees, one sample per bot in play per second. */
    final Histogram visibleEntities = new Histogram(1);

    final LongAdder loggedIn = new LongAdder();
    final LongAdder lost = new LongAdder();
    final Map<String, LongAdder> kicks = new ConcurrentHashMap<>();
    final Map<String, LongAdder> loginFailures = new ConcurrentHashMap<>();
    /**
     * The tries of gate bots ({@code docs/DESIGN-M3.md} §26 M3-E track D): how long the completed ones
     * took, from the first position in a frame to the 9th chunk on the new connection, and how many
     * failed, by reason. A try the run ended is neither.
     */
    final Histogram transferMillis = new Histogram(0.1);
    final Map<String, LongAdder> transferFailures = new ConcurrentHashMap<>();
    /**
     * The {@code connections} of the run file, one entry per connection a bot opened: {@code bot},
     * {@code connection} (1 for its first), {@code intent} ({@code login} or {@code transfer}),
     * {@code address}, {@code first_position} (where the first teleport put it, absent when it was
     * never spawned) and {@code stored_cookies} (key to the Base64 of the value, as stored during play).
     */
    final Queue<JsonObject> connectionLog = new ConcurrentLinkedQueue<>();

    void received(ConnectionState state, int id, int wireBytes) {
        inboundPackets.increment();
        inboundBytes.add(wireBytes);
        if (state == ConnectionState.PLAY && id >= 0 && id < playNames.size()) {
            playCount.incrementAndGet(id);
            playBytes.addAndGet(id, wireBytes);
        }
    }

    void sent(String packet) {
        outboundPackets.increment();
        outboundByPacket.computeIfAbsent(packet, _ -> new LongAdder()).increment();
    }

    static void count(Map<String, LongAdder> map, String key) {
        map.computeIfAbsent(key, _ -> new LongAdder()).increment();
    }

    static JsonObject counts(Map<String, LongAdder> map) {
        final JsonObject json = new JsonObject();
        new TreeMap<>(map).forEach((key, value) -> json.addProperty(key, value.sum()));
        return json;
    }

    /** The {@code transfers} of the run file, in the raw form {@code merge} reads. */
    JsonObject transfers() {
        final JsonObject json = new JsonObject();
        long failed = 0;
        for (LongAdder count : transferFailures.values()) failed += count.sum();
        json.addProperty("count", transferMillis.count() + failed);
        json.addProperty("completed", transferMillis.count());
        json.add("failed_by_reason", counts(transferFailures));
        json.add("transfer_ms", transferMillis.toJson());
        return json;
    }

    /** The entries of {@link #connectionLog}, by bot and then in the order it opened them. */
    JsonArray connections() {
        final List<JsonObject> sorted = new ArrayList<>(connectionLog);
        sorted.sort(Comparator.comparingInt((JsonObject entry) -> entry.get("bot").getAsInt())
                .thenComparingInt(entry -> entry.get("connection").getAsInt()));
        final JsonArray json = new JsonArray();
        sorted.forEach(json::add);
        return json;
    }

    JsonObject byPacket() {
        final JsonObject json = new JsonObject();
        for (int id = 0; id < playNames.size(); id++) {
            if (playCount.get(id) == 0) continue;
            final JsonObject packet = new JsonObject();
            packet.addProperty("count", playCount.get(id));
            packet.addProperty("wire_bytes", playBytes.get(id));
            json.add(playNames.get(id), packet);
        }
        return json;
    }
}
