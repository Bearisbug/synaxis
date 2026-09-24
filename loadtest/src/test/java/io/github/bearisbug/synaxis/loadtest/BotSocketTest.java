package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-PROD.md} {@code REQ-PROD-029}: bots against a real ticking server on loopback,
 * a plain and an encrypted login, a kick counted as a lost bot, chunk batch receipts that keep the chunks coming, and the packet
 * rates the run file reports.
 */
public class BotSocketTest {

    private static final int BOTS = 3;
    private static final String KICK = "kicked by the test";

    @Test
    @Timeout(90)
    public void walkingBotsAcknowledgeChunkBatchesAndGetMoreThanTheFirstNine(@TempDir Path directory) throws Exception {
        final JsonObject run = runAgainst(new Auth.Offline(false), "off", false, directory.resolve("bots-0.json"));
        assertEquals(BOTS, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
        assertEquals(0, run.getAsJsonObject("bots").get("lost").getAsInt(), run.toString());
        final JsonObject walk = run.getAsJsonObject("inbound").getAsJsonObject("chunks").getAsJsonObject("walk");
        final Histogram perBot = Histogram.fromJson(walk.getAsJsonObject("per_bot_packets"));
        assertEquals(BOTS, perBot.count());
        assertTrue(perBot.min() > 9, "every bot got past the first batch of 9: " + perBot.min());
        assertTrue(run.get("chunk_batches").getAsLong() > BOTS, "batches were acknowledged");
        assertTrue(Histogram.fromJson(run.getAsJsonObject("chunk_batch_rtt_ms")).count() > 0);
        assertTrue(walk.get("steady_packets").getAsLong() > 0, "walking brings new chunks after the warm up");

        // A position packet and a tick end every tick: 40 a second, plus the odd keep-alive and receipt
        final Histogram perSecond = Histogram.fromJson(run.getAsJsonObject("c2s").getAsJsonObject("per_bot_per_1s"));
        assertTrue(perSecond.count() >= BOTS * 3L, "whole seconds in play were counted: " + perSecond.count());
        final double median = perSecond.percentile(0.5);
        assertTrue(median >= 38 && median <= 46, "packets a second per bot: " + median);
        final Histogram perSeven = Histogram.fromJson(run.getAsJsonObject("c2s").getAsJsonObject("per_bot_per_7s"));
        assertTrue(perSeven.count() > 0, "seven second windows were counted");
        assertTrue(perSeven.percentile(0.5) >= 7 * 38, "a seven second window: " + perSeven.percentile(0.5));
        assertEquals(BOTS, Histogram.fromJson(run.getAsJsonObject("login_latency_ms").getAsJsonObject("off")).count(),
                "one login time per bot");
    }

    @Test
    @Timeout(90)
    public void botsAnswerTheEncryptionRequestOfAnEncryptingServer(@TempDir Path directory) throws Exception {
        final JsonObject run = runAgainst(new Auth.Offline(true), "on", false, directory.resolve("bots-0.json"));
        assertEquals(BOTS, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
        assertEquals(BOTS, Histogram.fromJson(run.getAsJsonObject("login_latency_ms").getAsJsonObject("on")).count());
        assertTrue(Histogram.fromJson(run.getAsJsonObject("inbound").getAsJsonObject("chunks").getAsJsonObject("walk")
                .getAsJsonObject("per_bot_packets")).min() > 9, "chunks decrypt and keep coming");
    }

    @Test
    @Timeout(90)
    public void aBotTheServerKicksCountsAsLostWithTheReason(@TempDir Path directory) throws Exception {
        final JsonObject run = runAgainst(new Auth.Offline(false), "off", true, directory.resolve("bots-0.json"));
        assertEquals(BOTS, run.getAsJsonObject("bots").get("logged_in").getAsInt(), run.toString());
        assertEquals(BOTS, run.getAsJsonObject("bots").get("lost").getAsInt(), run.toString());
        assertEquals(BOTS, run.getAsJsonObject("kicks_by_reason").get(KICK).getAsInt(), run.toString());
    }

    private static JsonObject runAgainst(Auth auth, String encryption, boolean kick, Path out) throws Exception {
        final ServerProcess process = ServerProcess.of(auth, ServerConfig.builder()
                .connectionGateTrustedNetworks("127.0.0.0/8").chunkViewDistance(4).build());
        final InstanceContainer world = process.instance().createInstanceContainer();
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(world);
            event.getPlayer().setRespawnPoint(new Pos(0.5, 40, 0.5));
        });
        if (kick) process.eventHandler().addListener(PlayerSpawnEvent.class, event -> process.scheduler()
                .buildTask(() -> event.getPlayer().kick(KICK)).delay(TaskSchedule.seconds(4)).schedule());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        final Thread ticker = Thread.ofPlatform().name("test-ticker").start(() -> {
            long next = System.nanoTime();
            while (!Thread.currentThread().isInterrupted()) {
                process.ticker().tick(System.nanoTime());
                next += TimeUnit.MILLISECONDS.toNanos(50);
                LockSupport.parkNanos(next - System.nanoTime());
            }
        });
        try {
            final int status = RunCommand.run(new Args(List.of("--port", Integer.toString(process.server().getPort()),
                    "--bots", Integer.toString(BOTS), "--duration", "12s", "--warmup", "3s", "--sample-every", "4s",
                    "--encryption", encryption, "--out", out.toString())));
            assertEquals(0, status);
            return Json.read(out);
        } finally {
            ticker.interrupt();
            ticker.join(5_000);
            process.stop();
        }
    }
}
