package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * {@code run}: drives N bots against a server and writes what they saw to {@code bots-<n>.json}
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}). Several processes, on one machine or several,
 * split the bots with {@code --bot-offset}; {@code merge} puts their files together.
 */
final class RunCommand {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private RunCommand() {
    }

    static int run(Args args) throws IOException, InterruptedException {
        final Bot.Settings settings = new Bot.Settings(
                args.string("host", "127.0.0.1"),
                args.integer("port", 25565),
                Profile.parse(args.string("profile", "walk")),
                args.onOff("encryption", false),
                (float) args.number("chunks-per-tick", 9.0),
                args.onOff("hello", false),
                args.seconds("warmup", 60),
                (int) (args.seconds("timeout", 30) * 1000));
        final int count = args.integer("bots", 10);
        final int offset = args.integer("bot-offset", 0);
        final double duration = args.seconds("duration", 600);
        final double loginRate = args.rate("login-rate", 20);
        final double sampleEvery = args.seconds("sample-every", 10);
        final Path out = Path.of(args.required("out"));
        args.requireAllRead();

        final BotStats stats = new BotStats();
        final List<Bot> bots = new ArrayList<>(count);
        final List<Thread> threads = new ArrayList<>(count);
        final Instant startedAt = Instant.now();
        final long start = System.nanoTime();
        final long end = start + (long) (duration * SECOND);
        final long cpuStart = processCpuNanos();
        System.out.printf(Locale.ROOT, "run: %d bots from %d, %s, encryption %s, against %s:%d for %.0f s%n", count,
                offset, settings.profile().label(), settings.encryption() ? "on" : "off", settings.host(),
                settings.port(), duration);

        final Thread starter = Thread.ofPlatform().name("bot-starter").start(() -> {
            for (int i = 0; i < count; i++) {
                final long due = start + (long) (i / loginRate * SECOND);
                final long wait = due - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                if (System.nanoTime() >= end) return;
                final Bot bot = new Bot(offset + i, settings, stats);
                synchronized (bots) {
                    bots.add(bot);
                    threads.add(Thread.ofVirtual().name("bot-" + bot.index).start(() -> bot.run(end)));
                }
            }
        });

        final JsonArray samples = new JsonArray();
        long nextSample = start + (long) (sampleEvery * SECOND);
        long lastSampleNanos = start;
        long lastSampleSent = 0;
        long lastSampleBytes = 0;
        long lastSampleCpu = cpuStart;
        for (long tick = start + SECOND; tick < end; tick += SECOND) {
            final long wait = tick - System.nanoTime();
            if (wait > 0) LockSupport.parkNanos(wait);
            final List<Bot> snapshot;
            synchronized (bots) {
                snapshot = List.copyOf(bots);
            }
            int inPlay = 0;
            for (Bot bot : snapshot) if (window(bot, stats)) inPlay++;
            if (System.nanoTime() >= nextSample) {
                final long now = System.nanoTime();
                final long sent = stats.outboundPackets.sum();
                final long bytes = stats.inboundBytes.sum();
                final long cpu = processCpuNanos();
                final double seconds = (now - lastSampleNanos) / 1e9;
                final JsonObject sample = new JsonObject();
                sample.addProperty("t", Math.round((now - start) / 1e8) / 10.0);
                sample.addProperty("bots_in_play", inPlay);
                sample.addProperty("c2s_packets_per_s", Histogram.round((sent - lastSampleSent) / seconds));
                sample.addProperty("s2c_bytes_per_s", Histogram.round((bytes - lastSampleBytes) / seconds));
                sample.addProperty("harness_cpu_percent", Histogram.round((cpu - lastSampleCpu) / 1e7 / seconds));
                sample.addProperty("load1", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
                samples.add(sample);
                System.out.println("run: " + sample);
                lastSampleNanos = now;
                lastSampleSent = sent;
                lastSampleBytes = bytes;
                lastSampleCpu = cpu;
                nextSample += (long) (sampleEvery * SECOND);
            }
        }
        starter.join();
        final List<Bot> all;
        synchronized (bots) {
            all = List.copyOf(bots);
        }
        for (Bot bot : all) bot.close();
        for (Thread thread : threads) thread.join(10_000);
        final double elapsed = (System.nanoTime() - start) / 1e9;
        final double cpuSeconds = (processCpuNanos() - cpuStart) / 1e9;

        final JsonObject json = new JsonObject();
        json.addProperty("kind", "bots");
        final JsonObject profile = new JsonObject();
        profile.addProperty("profile", settings.profile().label());
        profile.addProperty("bots", count);
        profile.addProperty("bot_offset", offset);
        profile.addProperty("encryption", settings.encryption() ? "on" : "off");
        profile.addProperty("chunks_per_tick", settings.chunksPerTick());
        profile.addProperty("duration_s", duration);
        profile.addProperty("login_rate_per_s", loginRate);
        profile.addProperty("warmup_s", settings.warmupSeconds());
        profile.addProperty("host", settings.host());
        profile.addProperty("port", settings.port());
        json.add("profile", profile);
        json.add("machine", machine());
        json.addProperty("started_at", startedAt.toString());
        json.addProperty("elapsed_s", Histogram.round(elapsed));

        final JsonObject botsJson = new JsonObject();
        botsJson.addProperty("requested", count);
        botsJson.addProperty("logged_in", stats.loggedIn.sum());
        botsJson.addProperty("lost", stats.lost.sum());
        botsJson.add("login_failures", BotStats.counts(stats.loginFailures));
        json.add("bots", botsJson);
        json.add("kicks_by_reason", BotStats.counts(stats.kicks));
        final JsonObject login = new JsonObject();
        login.add(settings.encryption() ? "on" : "off", stats.loginMillis.toJson());
        json.add("login_latency_ms", login);

        final JsonObject inbound = new JsonObject();
        inbound.addProperty("packets", stats.inboundPackets.sum());
        inbound.addProperty("wire_bytes", stats.inboundBytes.sum());
        inbound.addProperty("connection_seconds", Histogram.round(stats.connectionSeconds.sum()));
        inbound.add("by_packet", stats.byPacket());
        final JsonObject chunks = new JsonObject();
        final JsonObject profileChunks = new JsonObject();
        profileChunks.addProperty("packets", stats.chunkPackets.sum());
        profileChunks.addProperty("wire_bytes", stats.chunkBytes.sum());
        profileChunks.addProperty("connection_seconds", Histogram.round(stats.connectionSeconds.sum()));
        profileChunks.addProperty("steady_packets", stats.steadyChunkPackets.sum());
        profileChunks.addProperty("steady_wire_bytes", stats.steadyChunkBytes.sum());
        profileChunks.addProperty("steady_connection_seconds", Histogram.round(stats.steadyConnectionSeconds.sum()));
        profileChunks.add("per_bot_packets", stats.chunksPerBot.toJson());
        chunks.add(settings.profile().label(), profileChunks);
        inbound.add("chunks", chunks);
        json.add("inbound", inbound);
        json.add("chunk_batch_rtt_ms", stats.chunkBatchMillis.toJson());
        json.add("visible_entities", stats.visibleEntities.toJson());
        json.addProperty("chunk_batches", stats.chunkBatches.sum());

        final JsonObject outbound = new JsonObject();
        outbound.addProperty("packets", stats.outboundPackets.sum());
        outbound.add("by_packet", BotStats.counts(stats.outboundByPacket));
        outbound.add("per_bot_per_1s", stats.outboundPerSecond.toJson());
        outbound.add("per_bot_per_7s", stats.outboundPerSevenSeconds.toJson());
        json.add("c2s", outbound);

        final JsonObject harness = new JsonObject();
        harness.addProperty("cpu_seconds", Histogram.round(cpuSeconds));
        harness.addProperty("cpu_percent_mean", Histogram.round(cpuSeconds / elapsed * 100));
        harness.add("samples", samples);
        json.add("harness", harness);

        Json.write(out, json);
        System.out.printf(Locale.ROOT, "run: %d/%d logged in, %d lost, %d chunk packets; wrote %s%n",
                stats.loggedIn.sum(), count, stats.lost.sum(), stats.chunkPackets.sum(), out);
        return 0;
    }

    /**
     * Records the packets {@code bot} sent in the last second, and in the last seven once it has
     * been in play that long. A bot is only counted for whole seconds in play. Also samples how many
     * entities it sees.
     *
     * @return whether the bot is in play
     */
    static boolean window(Bot bot, BotStats stats) {
        if (!bot.inPlay()) return false;
        stats.visibleEntities.record((double) bot.visibleEntities());
        final long sent = bot.sent.get();
        if (bot.windowFilled < 0) {
            // The first look: the second it entered play in is only partly its own
            bot.lastSent = sent;
            bot.windowFilled = 0;
            return true;
        }
        final long delta = sent - bot.lastSent;
        bot.lastSent = sent;
        stats.outboundPerSecond.record((double) delta);
        bot.window[bot.windowFilled % bot.window.length] = delta;
        bot.windowFilled++;
        if (bot.windowFilled >= bot.window.length) {
            long seven = 0;
            for (long second : bot.window) seven += second;
            stats.outboundPerSevenSeconds.record((double) seven);
        }
        return true;
    }

    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
                ? os.getProcessCpuTime() : 0;
    }

    static JsonObject machine() {
        final JsonObject machine = new JsonObject();
        String name;
        try {
            name = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException _) {
            name = "unknown";
        }
        machine.addProperty("hostname", name);
        machine.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        machine.addProperty("cores", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os)
            machine.addProperty("memory_mib", os.getTotalMemorySize() >> 20);
        machine.addProperty("java", System.getProperty("java.version"));
        return machine;
    }
}
