package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code merge} ({@code API-PROD-025}): two bot processes and a sampler make one summary whose
 * percentiles are those of all the values, and whose verdict follows the criteria of the run,
 * the transfers of gate bots included.
 */
public class MergeTest {

    private static final MergeCommand.Criteria BASELINE = new MergeCommand.Criteria(50, 0, 4096, 9, 0);

    @Test
    public void twoBotFilesMergeIntoTheFiguresOfAllTheirBots() {
        final JsonObject summary = MergeCommand.merge(List.of(bots(0, 10, 20, 100), bots(1, 30, 40, 300)),
                server(12.5, 0, 3000), BASELINE);
        assertEquals(2, summary.getAsJsonObject("bots").get("logged_in").getAsLong());
        final JsonObject walk = summary.getAsJsonObject("inbound").getAsJsonObject("chunks").getAsJsonObject("walk");
        assertEquals(40, walk.get("packets").getAsLong());
        assertEquals(400, walk.get("wire_bytes").getAsLong());
        assertEquals(10.0, walk.getAsJsonObject("per_bot_packets").get("min").getAsDouble());
        // 40 and 60 a second from one file, 50 from the other: the median of all three is 50
        assertEquals(50.0, summary.getAsJsonObject("c2s").getAsJsonObject("per_bot_per_1s").get("p50").getAsDouble());
        assertEquals(60.0, summary.getAsJsonObject("c2s").getAsJsonObject("per_bot_per_1s").get("max").getAsDouble());
        assertTrue(summary.get("same_machine").getAsBoolean());
        assertTrue(summary.getAsJsonObject("verdict").get("pass").getAsBoolean(), summary.get("verdict").toString());
        // Files of a harness that did not count transfers merge as they did
        assertFalse(summary.has("transfers"));
        assertFalse(summary.getAsJsonObject("verdict").has("transfers_completed"));
    }

    @Test
    public void eachCriterionFailsTheVerdictOnItsOwn() {
        assertFalse(pass(List.of(bots(0, 10, 20, 100)), server(50.0, 0, 3000)), "a P99 of 50 ms is not under 50");
        assertFalse(pass(List.of(bots(0, 10, 20, 100)), server(12.5, 1, 3000)), "one tick over 50 ms");
        assertFalse(pass(List.of(bots(0, 10, 20, 100)), server(12.5, 0, 4096)), "an RSS of 4 GiB is not under it");
        assertFalse(pass(List.of(bots(0, 9, 20, 100)), server(12.5, 0, 3000)), "a bot stuck at the first 9 chunks");
        final JsonObject lostOne = bots(0, 10, 20, 100);
        lostOne.getAsJsonObject("bots").addProperty("lost", 1);
        assertFalse(pass(List.of(lostOne), server(12.5, 0, 3000)), "a bot lost during the run");
        assertFalse(pass(List.of(bots(0, 10, 20, 100)), null), "no server file");
        assertFalse(MergeCommand.merge(List.of(bots(0, 10, 20, 100)), server(12.5, 0, 3000),
                new MergeCommand.Criteria(50, 0, 4096, 9, 1)).getAsJsonObject("verdict").get("pass").getAsBoolean(),
                "a transfer asked for and none made");
        assertTrue(pass(List.of(bots(0, 10, 20, 100)), server(12.5, 0, 3000)));
    }

    /**
     * {@code docs/DESIGN-M3.md} §26 M3-E track D: the transfers of two bot processes make one
     * distribution, failures kept by reason, and the verdict asks for enough completed trips, the
     * samples of the P95 ({@code docs/DESIGN-M3.md} §15, the transfer row).
     */
    @Test
    public void theTransfersOfTwoFilesMergeIntoOneDistribution() {
        final JsonObject first = bots(0, 10, 20, 100);
        transfers(first, Map.of("no-transfer", 1L), 1000, 2000, 3000);
        final JsonObject second = bots(1, 30, 40, 300);
        transfers(second, Map.of("shard-full", 1L), 4000, 4500);
        final JsonObject summary = MergeCommand.merge(List.of(first, second), server(12.5, 0, 3000),
                new MergeCommand.Criteria(50, 0, 4096, 9, 5));
        final JsonObject transfers = summary.getAsJsonObject("transfers");
        assertEquals(7, transfers.get("count").getAsLong(), "five completed and two failed");
        assertEquals(5, transfers.get("completed").getAsLong());
        assertEquals(1, transfers.getAsJsonObject("failed_by_reason").get("no-transfer").getAsLong());
        assertEquals(1, transfers.getAsJsonObject("failed_by_reason").get("shard-full").getAsLong());
        final JsonObject millis = transfers.getAsJsonObject("transfer_ms");
        assertEquals(5, millis.get("count").getAsLong());
        assertEquals(3000.0, millis.get("p50_ms").getAsDouble());
        assertEquals(4500.0, millis.get("p95_ms").getAsDouble());
        assertEquals(4500.0, millis.get("p99_ms").getAsDouble());
        assertEquals(4500.0, millis.get("max_ms").getAsDouble());
        assertTrue(summary.getAsJsonObject("verdict").get("pass").getAsBoolean(), summary.get("verdict").toString());
        final JsonObject sixAsked = MergeCommand.merge(List.of(first, second), server(12.5, 0, 3000),
                new MergeCommand.Criteria(50, 0, 4096, 9, 6)).getAsJsonObject("verdict");
        assertFalse(sixAsked.get("pass").getAsBoolean(), "5 completed are fewer than 6, the 2 failed tries not counted");
        assertFalse(sixAsked.getAsJsonObject("transfers_completed").get("pass").getAsBoolean(), sixAsked.toString());
    }

    /**
     * {@code merge} exits with 2 when the transfer P95 is not under 5,000 ms: 18 trips of 1,000 ms and 2
     * of 5,001 put the P95 on 5,001; one slow trip in 20 leaves it at 1,000 whatever the maximum.
     */
    @Test
    public void aTransferP95Of5001MillisFailsTheMergeWithExitCode2(@TempDir Path directory) throws IOException {
        assertEquals(2, mergeExit(directory, 2, 20), "a P95 of 5,001 ms");
        assertEquals(0, mergeExit(directory, 1, 20), "a P95 of 1,000 ms and a maximum of 5,001");
        assertEquals(2, mergeExit(directory, 1, 21), "20 completed where 21 were asked for");
    }

    private static int mergeExit(Path directory, int slow, int minTransfers) throws IOException {
        final double[] millis = new double[20];
        for (int i = 0; i < millis.length; i++) millis[i] = i < millis.length - slow ? 1000 : 5001;
        final JsonObject bots = bots(0, 10, 20, 100);
        transfers(bots, Map.of(), millis);
        final Path botsFile = directory.resolve("bots-0.json");
        final Path serverFile = directory.resolve("server.json");
        Json.write(botsFile, bots);
        Json.write(serverFile, server(12.5, 0, 3000));
        return MergeCommand.run(new Args(List.of(botsFile.toString(), serverFile.toString(),
                "--min-transfers", Integer.toString(minTransfers),
                "--out", directory.resolve("summary.json").toString())));
    }

    /** The {@code transfers} of a bot file: the completed trips and the failed tries by reason. */
    private static void transfers(JsonObject file, Map<String, Long> failed, double... millis) {
        final Histogram histogram = new Histogram(0.1);
        for (double value : millis) histogram.record(value);
        final JsonObject transfers = new JsonObject();
        transfers.addProperty("count", millis.length + failed.values().stream().mapToLong(Long::longValue).sum());
        transfers.addProperty("completed", millis.length);
        final JsonObject reasons = new JsonObject();
        failed.forEach(reasons::addProperty);
        transfers.add("failed_by_reason", reasons);
        transfers.add("transfer_ms", histogram.toJson());
        file.add("transfers", transfers);
    }

    private static boolean pass(List<JsonObject> bots, @Nullable JsonObject server) {
        return MergeCommand.merge(bots, server, BASELINE).getAsJsonObject("verdict").get("pass").getAsBoolean();
    }

    /** One bot process of one bot that got {@code chunks} chunk packets. */
    private static JsonObject bots(int offset, long chunks, double loginMillis, long chunkBytes) {
        final JsonObject json = new JsonObject();
        json.addProperty("kind", "bots");
        final JsonObject profile = new JsonObject();
        profile.addProperty("bot_offset", offset);
        json.add("profile", profile);
        json.add("machine", machine());
        final JsonObject bots = new JsonObject();
        bots.addProperty("requested", 1);
        bots.addProperty("logged_in", 1);
        bots.addProperty("lost", 0);
        bots.add("login_failures", new JsonObject());
        json.add("bots", bots);
        json.add("kicks_by_reason", new JsonObject());
        final Histogram login = new Histogram(0.1);
        login.record(loginMillis);
        final JsonObject logins = new JsonObject();
        logins.add("off", login.toJson());
        json.add("login_latency_ms", logins);
        final JsonObject inbound = new JsonObject();
        inbound.addProperty("packets", chunks);
        inbound.addProperty("wire_bytes", chunkBytes);
        inbound.addProperty("connection_seconds", 10.0);
        inbound.add("by_packet", new JsonObject());
        final JsonObject walk = new JsonObject();
        walk.addProperty("packets", chunks);
        walk.addProperty("wire_bytes", chunkBytes);
        walk.addProperty("connection_seconds", 10.0);
        walk.addProperty("steady_packets", 0);
        walk.addProperty("steady_wire_bytes", 0);
        walk.addProperty("steady_connection_seconds", 0.0);
        final Histogram perBot = new Histogram(1);
        perBot.record((double) chunks);
        walk.add("per_bot_packets", perBot.toJson());
        final JsonObject profiles = new JsonObject();
        profiles.add("walk", walk);
        inbound.add("chunks", profiles);
        json.add("inbound", inbound);
        json.add("chunk_batch_rtt_ms", new Histogram(0.1).toJson());
        json.addProperty("chunk_batches", 1);
        final JsonObject c2s = new JsonObject();
        c2s.addProperty("packets", 400);
        c2s.add("by_packet", new JsonObject());
        final Histogram perSecond = new Histogram(1);
        if (offset == 0) {
            perSecond.record(40);
            perSecond.record(60);
        } else {
            perSecond.record(50);
        }
        c2s.add("per_bot_per_1s", perSecond.toJson());
        c2s.add("per_bot_per_7s", new Histogram(1).toJson());
        json.add("c2s", c2s);
        final JsonObject harness = new JsonObject();
        harness.addProperty("cpu_seconds", 1.0);
        harness.add("samples", new JsonArray());
        json.add("harness", harness);
        return json;
    }

    private static JsonObject server(double p99, long over50, double rss) {
        final JsonObject json = new JsonObject();
        json.addProperty("kind", "server");
        json.add("machine", machine());
        final JsonObject overall = new JsonObject();
        overall.addProperty("p99_ms", p99);
        overall.addProperty("over_50ms", over50);
        final JsonObject ticks = new JsonObject();
        ticks.add("overall", overall);
        json.add("ticks", ticks);
        final JsonObject heap = new JsonObject();
        heap.addProperty("rss_mib_max", rss);
        json.add("heap", heap);
        final JsonArray samples = new JsonArray();
        final JsonObject sample = new JsonObject();
        sample.addProperty("load1", 3.5);
        samples.add(sample);
        json.add("samples", samples);
        return json;
    }

    private static JsonObject machine() {
        final JsonObject machine = new JsonObject();
        machine.addProperty("hostname", "box");
        return machine;
    }
}
