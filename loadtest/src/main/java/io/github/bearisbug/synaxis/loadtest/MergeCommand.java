package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code merge}: puts the {@code bots-*.json} of every bot process and the {@code server.json} of
 * the sampler into one {@code summary.json} ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}),
 * with a verdict against the criteria of the run ({@code design-ops-scale.md} ⑤ 5.4).
 */
final class MergeCommand {

    private MergeCommand() {
    }

    static int run(Args args) throws IOException {
        final Path out = Path.of(args.required("out"));
        final Criteria criteria = new Criteria(args.number("tick-p99-ms", 50), args.integer("max-over-50ms", 0),
                args.number("rss-mib", 4096), args.integer("min-chunks", 9));
        args.requireAllRead();
        final List<JsonObject> bots = new ArrayList<>();
        JsonObject server = null;
        for (String file : args.positional) {
            final JsonObject json = Json.read(Path.of(file));
            switch (json.get("kind").getAsString()) {
                case "bots" -> bots.add(json);
                case "server" -> {
                    if (server != null) throw new IllegalArgumentException("two server files: " + file);
                    server = json;
                }
                default -> throw new IllegalArgumentException(file + " is neither a bots nor a server file");
            }
        }
        if (bots.isEmpty()) throw new IllegalArgumentException("no bots-*.json given");
        final JsonObject summary = merge(bots, server, criteria);
        Json.write(out, summary);
        System.out.println("merge: verdict " + summary.getAsJsonObject("verdict"));
        System.out.println("merge: wrote " + out);
        return summary.getAsJsonObject("verdict").get("pass").getAsBoolean() ? 0 : 2;
    }

    /**
     * The thresholds a run is judged on.
     *
     * @param tickP99Millis   the tick P99 must stay under this
     * @param maxOver50Millis how many ticks may take more than 50 ms
     * @param rssMib          the server's resident set must stay under this
     * @param minChunks       every bot must receive more chunk packets than this
     */
    record Criteria(double tickP99Millis, int maxOver50Millis, double rssMib, int minChunks) {
    }

    static JsonObject merge(List<JsonObject> bots, @Nullable JsonObject server, Criteria criteria) {
        final JsonObject summary = new JsonObject();
        final JsonArray profiles = new JsonArray();
        final JsonArray harness = new JsonArray();
        long requested = 0;
        long loggedIn = 0;
        long lost = 0;
        final Map<String, Long> loginFailures = new TreeMap<>();
        final Map<String, Long> kicks = new TreeMap<>();
        final Map<String, Histogram> login = new TreeMap<>();
        final Histogram batches = new Histogram(0.1);
        long batchCount = 0;
        long inboundPackets = 0;
        long inboundBytes = 0;
        double connectionSeconds = 0;
        final Map<String, long[]> byPacket = new TreeMap<>();
        final Map<String, ChunkTotals> chunks = new TreeMap<>();
        long c2sPackets = 0;
        final Map<String, Long> c2sByPacket = new TreeMap<>();
        final Histogram perSecond = new Histogram(1);
        final Histogram perSeven = new Histogram(1);
        for (JsonObject file : bots) {
            profiles.add(file.get("profile"));
            final JsonObject cost = file.getAsJsonObject("harness").deepCopy();
            cost.remove("samples");
            cost.add("machine", file.get("machine"));
            harness.add(cost);
            final JsonObject counts = file.getAsJsonObject("bots");
            requested += counts.get("requested").getAsLong();
            loggedIn += counts.get("logged_in").getAsLong();
            lost += counts.get("lost").getAsLong();
            sum(loginFailures, counts.getAsJsonObject("login_failures"));
            sum(kicks, file.getAsJsonObject("kicks_by_reason"));
            for (Map.Entry<String, JsonElement> mode : file.getAsJsonObject("login_latency_ms").entrySet())
                login.computeIfAbsent(mode.getKey(), _ -> new Histogram(0.1))
                        .merge(Histogram.fromJson(mode.getValue().getAsJsonObject()));
            batches.merge(Histogram.fromJson(file.getAsJsonObject("chunk_batch_rtt_ms")));
            batchCount += file.get("chunk_batches").getAsLong();
            final JsonObject inbound = file.getAsJsonObject("inbound");
            inboundPackets += inbound.get("packets").getAsLong();
            inboundBytes += inbound.get("wire_bytes").getAsLong();
            connectionSeconds += inbound.get("connection_seconds").getAsDouble();
            for (Map.Entry<String, JsonElement> packet : inbound.getAsJsonObject("by_packet").entrySet()) {
                final long[] total = byPacket.computeIfAbsent(packet.getKey(), _ -> new long[2]);
                total[0] += packet.getValue().getAsJsonObject().get("count").getAsLong();
                total[1] += packet.getValue().getAsJsonObject().get("wire_bytes").getAsLong();
            }
            for (Map.Entry<String, JsonElement> profile : inbound.getAsJsonObject("chunks").entrySet())
                chunks.computeIfAbsent(profile.getKey(), _ -> new ChunkTotals()).add(profile.getValue().getAsJsonObject());
            final JsonObject c2s = file.getAsJsonObject("c2s");
            c2sPackets += c2s.get("packets").getAsLong();
            sum(c2sByPacket, c2s.getAsJsonObject("by_packet"));
            perSecond.merge(Histogram.fromJson(c2s.getAsJsonObject("per_bot_per_1s")));
            perSeven.merge(Histogram.fromJson(c2s.getAsJsonObject("per_bot_per_7s")));
        }
        summary.add("profile", profiles);
        final JsonObject botCounts = new JsonObject();
        botCounts.addProperty("requested", requested);
        botCounts.addProperty("logged_in", loggedIn);
        botCounts.addProperty("lost", lost);
        botCounts.add("login_failures", json(loginFailures));
        summary.add("bots", botCounts);
        summary.add("kicks_by_reason", json(kicks));
        final JsonObject loginJson = new JsonObject();
        login.forEach((mode, histogram) -> loginJson.add(mode, histogram.distribution("_ms")));
        summary.add("login_latency_ms", loginJson);

        final JsonObject inbound = new JsonObject();
        inbound.addProperty("packets", inboundPackets);
        inbound.addProperty("wire_bytes", inboundBytes);
        inbound.addProperty("connection_seconds", Histogram.round(connectionSeconds));
        inbound.addProperty("bytes_per_s_per_bot", connectionSeconds == 0 ? 0
                : Histogram.round(inboundBytes / connectionSeconds));
        final JsonObject chunkJson = new JsonObject();
        long fewestChunks = Long.MAX_VALUE;
        for (Map.Entry<String, ChunkTotals> entry : chunks.entrySet()) {
            chunkJson.add(entry.getKey(), entry.getValue().toJson());
            fewestChunks = Math.min(fewestChunks, (long) entry.getValue().perBot.min());
        }
        inbound.add("chunks", chunkJson);
        final JsonObject packets = new JsonObject();
        byPacket.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).forEach(entry -> {
            final JsonObject packet = new JsonObject();
            packet.addProperty("count", entry.getValue()[0]);
            packet.addProperty("wire_bytes", entry.getValue()[1]);
            packets.add(entry.getKey(), packet);
        });
        inbound.add("by_packet", packets);
        summary.add("inbound", inbound);
        summary.add("chunk_batch_rtt_ms", batches.distribution("_ms"));
        summary.addProperty("chunk_batches", batchCount);
        final JsonObject c2s = new JsonObject();
        c2s.addProperty("packets", c2sPackets);
        c2s.addProperty("packets_per_s_per_bot", connectionSeconds == 0 ? 0 : Histogram.round(c2sPackets / connectionSeconds));
        c2s.add("by_packet", json(c2sByPacket));
        c2s.add("per_bot_per_1s", perSecond.distribution(""));
        c2s.add("per_bot_per_7s", perSeven.distribution(""));
        summary.add("c2s", c2s);
        summary.add("harness", harness);

        final JsonObject verdict = new JsonObject();
        boolean pass = lost == 0 && loggedIn == requested;
        verdict.add("bots", check(loggedIn == requested && lost == 0, loggedIn + " of " + requested + " logged in, "
                + lost + " lost"));
        final boolean chunksOk = fewestChunks > criteria.minChunks();
        verdict.add("chunks_per_bot_min", check(chunksOk, fewestChunks + " > " + criteria.minChunks()));
        pass &= chunksOk;
        if (server != null) {
            for (String key : List.of("machine", "pid", "started_at", "elapsed_s", "ticks", "gc", "heap",
                    "connection_gate", "dispatcher_busy_ratio", "traffic", "error"))
                if (server.has(key)) summary.add(key.equals("machine") ? "server_machine" : key, server.get(key));
            final String botHost = bots.getFirst().getAsJsonObject("machine").get("hostname").getAsString();
            summary.addProperty("same_machine", botHost.equals(
                    server.getAsJsonObject("machine").get("hostname").getAsString()));
            summary.add("load1", load(server.getAsJsonArray("samples")));
            summary.add("server_samples", server.get("samples"));
            if (server.has("ticks")) {
                final JsonObject overall = server.getAsJsonObject("ticks").getAsJsonObject("overall");
                final double p99 = overall.has("p99_ms") ? overall.get("p99_ms").getAsDouble() : Double.NaN;
                final long over = overall.has("over_50ms") ? overall.get("over_50ms").getAsLong() : -1;
                final boolean p99Ok = p99 < criteria.tickP99Millis();
                final boolean overOk = over >= 0 && over <= criteria.maxOver50Millis();
                verdict.add("tick_p99_ms", check(p99Ok, p99 + " < " + criteria.tickP99Millis()));
                verdict.add("ticks_over_50ms", check(overOk, over + " <= " + criteria.maxOver50Millis()));
                pass &= p99Ok && overOk;
            } else {
                verdict.add("tick_p99_ms", check(false, "no ticks in the server file"));
                pass = false;
            }
            final double rss = server.getAsJsonObject("heap").get("rss_mib_max").getAsDouble();
            final boolean rssOk = rss < criteria.rssMib();
            verdict.add("rss_mib_max", check(rssOk, rss + " < " + criteria.rssMib()));
            pass &= rssOk;
        } else {
            verdict.add("server", check(false, "no server.json given"));
            pass = false;
        }
        verdict.addProperty("pass", pass);
        summary.add("verdict", verdict);
        return summary;
    }

    private static JsonObject load(JsonArray samples) {
        double min = Double.MAX_VALUE;
        double max = 0;
        for (JsonElement sample : samples) {
            final double load = sample.getAsJsonObject().get("load1").getAsDouble();
            min = Math.min(min, load);
            max = Math.max(max, load);
        }
        final JsonObject load = new JsonObject();
        load.addProperty("min", samples.isEmpty() ? 0 : Histogram.round(min));
        load.addProperty("max", Histogram.round(max));
        return load;
    }

    private static JsonObject check(boolean pass, String detail) {
        final JsonObject check = new JsonObject();
        check.addProperty("pass", pass);
        check.addProperty("detail", detail);
        return check;
    }

    private static void sum(Map<String, Long> totals, JsonObject counts) {
        for (Map.Entry<String, JsonElement> entry : counts.entrySet())
            totals.merge(entry.getKey(), entry.getValue().getAsLong(), Long::sum);
    }

    private static JsonObject json(Map<String, Long> counts) {
        final JsonObject json = new JsonObject();
        counts.forEach(json::addProperty);
        return json;
    }

    /** The chunk figures of one movement profile over every bot process that ran it. */
    private static final class ChunkTotals {
        long packets;
        long bytes;
        double seconds;
        long steadyPackets;
        long steadyBytes;
        double steadySeconds;
        final Histogram perBot = new Histogram(1);

        void add(JsonObject json) {
            packets += json.get("packets").getAsLong();
            bytes += json.get("wire_bytes").getAsLong();
            seconds += json.get("connection_seconds").getAsDouble();
            steadyPackets += json.get("steady_packets").getAsLong();
            steadyBytes += json.get("steady_wire_bytes").getAsLong();
            steadySeconds += json.get("steady_connection_seconds").getAsDouble();
            perBot.merge(Histogram.fromJson(json.getAsJsonObject("per_bot_packets")));
        }

        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("packets", packets);
            json.addProperty("wire_bytes", bytes);
            json.addProperty("connection_seconds", Histogram.round(seconds));
            json.addProperty("kb_per_s_per_bot", seconds == 0 ? 0 : Histogram.round(bytes / 1024.0 / seconds));
            json.addProperty("packets_per_s_per_bot", seconds == 0 ? 0 : Histogram.round(packets / seconds));
            json.addProperty("steady_packets", steadyPackets);
            json.addProperty("steady_wire_bytes", steadyBytes);
            json.addProperty("steady_connection_seconds", Histogram.round(steadySeconds));
            json.addProperty("steady_kb_per_s_per_bot", steadySeconds == 0 ? 0
                    : Histogram.round(steadyBytes / 1024.0 / steadySeconds));
            json.addProperty("steady_packets_per_s_per_bot", steadySeconds == 0 ? 0
                    : Histogram.round(steadyPackets / steadySeconds));
            final JsonObject perBotJson = perBot.distribution("");
            perBotJson.addProperty("min", perBot.min());
            json.add("per_bot_packets", perBotJson);
            return json;
        }
    }
}
