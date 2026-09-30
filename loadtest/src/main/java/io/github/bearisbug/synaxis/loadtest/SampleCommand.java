package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code sample}: watches the server process from its own machine and writes {@code server.json}
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}). Every tick, with the time it waited to acquire,
 * comes from the engine's JFR event {@code synaxis.ServerTickTime}, recorded for the run and deleted
 * once read; the process figures from {@code ps}; the gate, the traffic and the busy time of each
 * tick thread from the metrics endpoint ({@code API-PROD-015}) when {@code --metrics} names it.
 */
final class SampleCommand {

    static final String TICK_EVENT = "synaxis.ServerTickTime";
    /** The field of {@link #TICK_EVENT} holding the acquisition wait of the tick ({@code API-PROD-016}). */
    static final String ACQUIRE_FIELD = "acquisitionTime";
    private static final String GATE = "synaxis_gate_rejections_total{reason=\"";
    private static final String TICK_SUM = "synaxis_tick_duration_seconds_sum{thread=\"";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SampleCommand() {
    }

    static int run(Args args) throws IOException, InterruptedException {
        final long pid = Long.parseLong(args.required("pid"));
        final double duration = args.seconds("duration", 600);
        final double every = args.seconds("every", 10);
        final String metrics = args.string("metrics", "");
        final Path out = Path.of(args.required("out"));
        final boolean keepJfr = args.onOff("keep-jfr", false);
        args.requireAllRead();
        final ProcessHandle process = ProcessHandle.of(pid)
                .orElseThrow(() -> new IllegalArgumentException("no process " + pid));

        final Path jfr = Files.createTempFile("synaxis-loadtest-" + pid + "-", ".jfr");
        final String started = shell("jcmd", Long.toString(pid), "JFR.start", "name=loadtest", "settings=default",
                "filename=" + jfr, "maxsize=1g");
        if (!started.contains("Started recording"))
            throw new IllegalStateException("JFR.start failed: " + started.strip());

        final Instant startedAt = Instant.now();
        final long start = System.nanoTime();
        final JsonArray samples = new JsonArray();
        @Nullable Map<String, Double> firstMetrics = null;
        @Nullable Map<String, Double> lastMetrics = null;
        double firstMetricsAt = 0;
        double lastMetricsAt = 0;
        double rssMax = 0;
        double heapMax = 0;
        long next = start;
        while (process.isAlive() && System.nanoTime() - start < duration * 1e9) {
            final long wait = next - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(Math.min(wait, (long) ((duration * 1e9) - (System.nanoTime() - start))));
            next += (long) (every * 1e9);
            final double t = (System.nanoTime() - start) / 1e9;
            final JsonObject sample = new JsonObject();
            sample.addProperty("t", Math.round(t * 10) / 10.0);
            final List<String> ps = WHITESPACE.splitAsStream(
                    shell("ps", "-o", "%cpu=,rss=", "-p", Long.toString(pid)).strip()).toList();
            if (ps.size() == 2) {
                sample.addProperty("cpu_percent", Double.parseDouble(ps.get(0)));
                final double rss = Double.parseDouble(ps.get(1)) / 1024.0;
                sample.addProperty("rss_mib", Histogram.round(rss));
                rssMax = Math.max(rssMax, rss);
            }
            sample.addProperty("threads", threads(pid));
            sample.addProperty("fds", fds(pid));
            sample.addProperty("load1", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
            if (!metrics.isEmpty()) {
                final Map<String, Double> scraped = scrape(metrics);
                if (scraped != null) {
                    if (firstMetrics == null) {
                        firstMetrics = scraped;
                        firstMetricsAt = t;
                    }
                    lastMetrics = scraped;
                    lastMetricsAt = t;
                    sample.addProperty("players", scraped.getOrDefault("synaxis_players_online", 0.0));
                    sample.addProperty("chunks_loaded", scraped.getOrDefault("synaxis_chunks_loaded", 0.0));
                    sample.addProperty("entities", scraped.getOrDefault("synaxis_entities", 0.0));
                    final double heap = scraped.entrySet().stream()
                            .filter(entry -> entry.getKey().startsWith("jvm_memory_used_bytes{area=\"heap\""))
                            .mapToDouble(Map.Entry::getValue).sum() / (1 << 20);
                    sample.addProperty("heap_used_mib", Histogram.round(heap));
                    heapMax = Math.max(heapMax, heap);
                    sample.addProperty("packets_in_total", scraped.getOrDefault("synaxis_packets_total{direction=\"in\"}", 0.0));
                    sample.addProperty("packets_out_total", scraped.getOrDefault("synaxis_packets_total{direction=\"out\"}", 0.0));
                    sample.addProperty("bytes_out_total", scraped.getOrDefault("synaxis_bytes_total{direction=\"out\"}", 0.0));
                }
            }
            samples.add(sample);
            System.out.println("sample: " + sample);
        }
        final double elapsed = (System.nanoTime() - start) / 1e9;

        final JsonObject json = new JsonObject();
        json.addProperty("kind", "server");
        json.addProperty("pid", pid);
        json.add("machine", RunCommand.machine());
        json.addProperty("started_at", startedAt.toString());
        json.addProperty("elapsed_s", Histogram.round(elapsed));
        if (process.isAlive()) {
            shell("jcmd", Long.toString(pid), "JFR.dump", "name=loadtest", "filename=" + jfr);
            shell("jcmd", Long.toString(pid), "JFR.stop", "name=loadtest");
            final JsonObject gc = new JsonObject();
            json.add("ticks", ticks(jfr, startedAt, gc));
            json.add("gc", gc);
        } else {
            json.addProperty("error", "the server process exited before the sampler ended");
        }
        if (keepJfr) json.addProperty("jfr", jfr.toString());
        else Files.deleteIfExists(jfr);

        final JsonObject heap = new JsonObject();
        heap.addProperty("rss_mib_max", Histogram.round(rssMax));
        heap.addProperty("heap_used_mib_max", Histogram.round(heapMax));
        json.add("heap", heap);
        if (firstMetrics != null && lastMetrics != null) {
            json.add("connection_gate", gate(lastMetrics));
            json.add("dispatcher_busy_ratio", busy(firstMetrics, lastMetrics, lastMetricsAt - firstMetricsAt));
            final JsonObject traffic = new JsonObject();
            final double span = lastMetricsAt - firstMetricsAt;
            for (String series : List.of("synaxis_packets_total{direction=\"in\"}", "synaxis_packets_total{direction=\"out\"}",
                    "synaxis_bytes_total{direction=\"in\"}", "synaxis_bytes_total{direction=\"out\"}")) {
                final String key = series.replace("synaxis_", "").replace("_total{direction=\"", "_").replace("\"}", "_per_s");
                traffic.addProperty(key, span <= 0 ? 0 : Histogram.round(
                        (lastMetrics.getOrDefault(series, 0.0) - firstMetrics.getOrDefault(series, 0.0)) / span));
            }
            json.add("traffic", traffic);
        }
        json.add("samples", samples);
        Json.write(out, json);
        System.out.println("sample: wrote " + out);
        return 0;
    }

    /**
     * The ticks of the recording: every one, and each minute of the run on its own.
     *
     * @param jfr       the recording
     * @param startedAt when sampling began, minute 0
     * @param gc        filled with the distribution of the pauses of every collection
     * @return count, overall distribution, per minute distributions and the distribution of the
     * acquisition waits
     */
    static JsonObject ticks(Path jfr, Instant startedAt, JsonObject gc) throws IOException {
        final Ticks ticks = new Ticks(startedAt);
        final Histogram pauses = new Histogram(0.001);
        try (RecordingFile recording = new RecordingFile(jfr)) {
            while (recording.hasMoreEvents()) {
                final RecordedEvent event = recording.readEvent();
                final String name = event.getEventType().getName();
                if (name.equals("jdk.GarbageCollection")) {
                    pauses.record(event.getDuration("sumOfPauses").toNanos() / 1e6);
                } else if (name.equals(TICK_EVENT)) {
                    ticks.record(event.getStartTime(), event.getDuration().toNanos() / 1e6);
                    // A server built before the field existed records none
                    if (event.hasField(ACQUIRE_FIELD)) ticks.acquire(event.getDuration(ACQUIRE_FIELD).toNanos() / 1e6);
                }
            }
        }
        pauses.distribution("_ms").entrySet().forEach(entry -> gc.add(entry.getKey(), entry.getValue()));
        return ticks.toJson();
    }

    /**
     * The distributions of the ticks, overall and per minute from {@code startedAt}, and of their
     * acquisition waits.
     */
    static final class Ticks {

        private final Instant startedAt;
        private final Histogram overall = new Histogram(0.001);
        private final Map<Long, Histogram> perMinute = new TreeMap<>();
        private final Histogram acquire = new Histogram(0.001);

        Ticks(Instant startedAt) {
            this.startedAt = startedAt;
        }

        void record(Instant start, double millis) {
            overall.record(millis);
            final long minute = Math.max(0, Duration.between(startedAt, start).toMinutes());
            perMinute.computeIfAbsent(minute, _ -> new Histogram(0.001)).record(millis);
        }

        /** The acquisition wait of one tick, {@code TickMonitor#getAcquisitionTime}. */
        void acquire(double millis) {
            acquire.record(millis);
        }

        JsonObject toJson() {
            final JsonObject ticks = new JsonObject();
            ticks.addProperty("count", overall.count());
            ticks.add("overall", overall.distribution("_ms"));
            final JsonObject minutes = new JsonObject();
            perMinute.forEach((minute, histogram) -> minutes.add(Long.toString(minute), histogram.distribution("_ms")));
            ticks.add("per_minute", minutes);
            ticks.add("acquire_ms", acquire.count() == 0 ? JsonNull.INSTANCE : acquire.distribution("_ms"));
            return ticks;
        }
    }

    static JsonObject gate(Map<String, Double> metrics) {
        final JsonObject gate = new JsonObject();
        metrics.forEach((series, value) -> {
            if (series.startsWith(GATE)) gate.addProperty(series.substring(GATE.length(), series.length() - 2), value);
        });
        return gate;
    }

    /** Each tick thread's share of wall time spent ticking, over the span between two scrapes. */
    static JsonObject busy(Map<String, Double> first, Map<String, Double> last, double span) {
        final JsonObject busy = new JsonObject();
        if (span <= 0) return busy;
        last.forEach((series, value) -> {
            if (!series.startsWith(TICK_SUM)) return;
            final String thread = series.substring(TICK_SUM.length(), series.length() - 2);
            busy.addProperty(thread, Histogram.round((value - first.getOrDefault(series, 0.0)) / span));
        });
        return busy;
    }

    /**
     * The series of a Prometheus text by their full name with labels, e.g.
     * {@code synaxis_packets_total{direction="in"}}.
     */
    static Map<String, Double> parse(String text) {
        final Map<String, Double> series = new TreeMap<>();
        for (String line : text.lines().toList()) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            final int space = line.lastIndexOf(' ');
            if (space < 0) continue;
            try {
                series.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
            } catch (NumberFormatException _) {
                // a value this parser does not know, such as NaN written differently, is left out
            }
        }
        return series;
    }

    private static @Nullable Map<String, Double> scrape(String url) {
        try {
            final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(2)).build();
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parse(response.body()) : null;
        } catch (IOException _) {
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static int threads(long pid) {
        final Path status = Path.of("/proc", Long.toString(pid), "status");
        if (Files.isReadable(status)) {
            try (Stream<String> lines = Files.lines(status)) {
                return lines.filter(line -> line.startsWith("Threads:"))
                        .mapToInt(line -> Integer.parseInt(line.substring(8).strip())).findFirst().orElse(0);
            } catch (IOException _) {
                return 0;
            }
        }
        return Math.max(0, (int) shell("ps", "-M", "-p", Long.toString(pid)).lines().count() - 1);
    }

    private static int fds(long pid) {
        final Path fd = Path.of("/proc", Long.toString(pid), "fd");
        if (Files.isDirectory(fd)) {
            try (Stream<Path> entries = Files.list(fd)) {
                return (int) entries.count();
            } catch (IOException _) {
                return 0;
            }
        }
        return Math.max(0, (int) shell("lsof", "-p", Long.toString(pid), "-n", "-P").lines().count() - 1);
    }

    static String shell(String... command) {
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(120, TimeUnit.SECONDS)) process.destroyForcibly();
            return output;
        } catch (IOException _) {
            return "";
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
