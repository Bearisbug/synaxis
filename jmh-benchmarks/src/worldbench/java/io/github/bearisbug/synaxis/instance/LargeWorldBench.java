package io.github.bearisbug.synaxis.instance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.Light;
import io.github.bearisbug.synaxis.instance.light.LightCompute;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Measures the per-chunk costs of a whole map: ChunkDataPacket size, first serialization time and
 * retained heap on synthetic terrain, whole-map {@link LightingChunk#relight} time and heap, and the
 * 3x3 local recompute after a single block change.
 * <p>
 * The driver (no arguments, or {@code --jobs}) runs every job in its own child JVM, with the same
 * fixed heap, so that one job's garbage, JIT profile and heap sizing cannot leak into the next.
 * Each child prints one {@code RESULT <json>} line; the driver writes them all, with the machine
 * description, to one JSON file. How to run it and what every field means: jmh-benchmarks/README.md.
 */
public final class LargeWorldBench {

    private static final String RESULT_PREFIX = "RESULT ";
    /** The engine default (ServerProcessImpl#compressionThreshold). */
    private static final int COMPRESSION_THRESHOLD = 256;
    private static final int LIGHT_ARRAY_BYTES = 2048 + 16; // byte[2048] plus the array header

    /** The default job list. */
    private static final List<String> DEFAULT_JOBS = List.of(
            "packet h=384 t=natural p=opt",
            "packet h=384 t=natural p=setall",
            "packet h=384 t=solid p=opt",
            "packet h=256 t=natural p=opt",
            "packet h=256 t=solid p=opt",
            "packet h=64 t=natural p=opt",
            "packet h=64 t=flat40 p=opt",
            "relight h=384 t=natural n=1024",
            "relight h=384 t=natural n=4096",
            "relight h=384 t=natural n=16384",
            "relight h=256 t=natural n=1024",
            "relight h=256 t=natural n=4096",
            "relight h=256 t=natural n=16384",
            "relight h=384 t=solid n=4096",
            "relight h=384 t=natural n=4096 p=setall",
            "relight h=64 t=natural n=4096",
            "relight h=64 t=natural n=4096 p=setall",
            "relight h=64 t=flat40 n=4096",
            "relight h=64 t=flat40 n=4096 p=setall",
            "local h=384 t=natural",
            "local h=256 t=natural"
    );

    private static final List<String> QUICK_JOBS = List.of(
            "packet h=384 t=natural p=opt grid=8 rounds=1 warmup=0",
            "relight h=384 t=natural n=256 rounds=1",
            "local h=384 t=natural grid=12 samples=30 warmupSamples=6"
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern JOB_SEPARATOR = Pattern.compile(";");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s,]+");

    private LargeWorldBench() {}

    public static void main(String[] args) throws Exception {
        final Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            if (arg.equals("--quick")) {
                options.put("quick", "true");
            } else if (arg.startsWith("--") && i + 1 < args.length) {
                options.put(arg.substring(2), args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (options.containsKey("child")) {
            final JsonObject result = runJob(options.get("child"));
            System.out.println(RESULT_PREFIX + new Gson().toJson(result));
            System.out.flush();
            System.exit(0);
        }
        drive(options);
    }

    // ------------------------------------------------------------------ driver

    private static void drive(Map<String, String> options) throws IOException, InterruptedException {
        final List<String> jobs;
        if (options.containsKey("jobs")) {
            jobs = JOB_SEPARATOR.splitAsStream(options.get("jobs")).toList();
        } else {
            jobs = options.containsKey("quick") ? QUICK_JOBS : DEFAULT_JOBS;
        }
        final String heap = options.getOrDefault("heap", "6g");
        final List<String> jvmArgs = List.of("-Xms" + heap, "-Xmx" + heap, "-XX:+UseG1GC", "-XX:+AlwaysPreTouch");
        final Path out = Path.of(options.getOrDefault("out",
                "build/worldbench/worldbench-" + Instant.now().toString().replace(':', '-') + ".json"));

        final JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("startedAt", Instant.now().toString());
        report.add("machine", machine());
        final JsonArray jvm = new JsonArray();
        jvmArgs.forEach(jvm::add);
        report.add("childJvmArgs", jvm);
        final JsonArray results = new JsonArray();
        report.add("jobs", results);

        final String java = ProcessHandle.current().info().command().orElseThrow();
        final String classpath = System.getProperty("java.class.path");
        for (String job : jobs) {
            final String spec = job.trim();
            System.out.println("== " + spec);
            final List<String> command = new ArrayList<>();
            command.add(java);
            command.addAll(jvmArgs);
            command.add("-cp");
            command.add(classpath);
            command.add(LargeWorldBench.class.getName());
            command.add("--child");
            command.add(spec);
            final Process child = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            JsonObject result = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(RESULT_PREFIX)) {
                        result = JsonParser.parseString(line.substring(RESULT_PREFIX.length())).getAsJsonObject();
                    } else {
                        System.out.println("   " + line);
                    }
                }
            }
            final int exit = child.waitFor();
            if (exit != 0 || result == null) {
                throw new IllegalStateException("Job failed (exit " + exit + "): " + spec);
            }
            System.out.println("   " + summary(result));
            results.add(result);
        }
        report.addProperty("finishedAt", Instant.now().toString());
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, GSON.toJson(report), StandardCharsets.UTF_8);
        System.out.println("Results written to " + out.toAbsolutePath());
    }

    private static String summary(JsonObject result) {
        final JsonObject copy = result.deepCopy();
        copy.remove("rounds");
        copy.remove("breakdown");
        return new Gson().toJson(copy);
    }

    private static JsonObject machine() {
        final JsonObject machine = new JsonObject();
        machine.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        machine.addProperty("cpu", cpuName());
        machine.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
            machine.addProperty("physicalMemoryGiB", os.getTotalMemorySize() / (1024.0 * 1024 * 1024));
        }
        machine.addProperty("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
        machine.addProperty("gitCommit", command("git", "rev-parse", "HEAD"));
        return machine;
    }

    private static String cpuName() {
        final String mac = command("sysctl", "-n", "machdep.cpu.brand_string");
        if (!mac.isEmpty()) return mac;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
                if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).trim();
            }
            return "unknown";
        } catch (IOException _) {
            return "unknown"; // Neither macOS nor Linux: the core count is still recorded
        }
    }

    private static String command(String... command) {
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : "";
        } catch (IOException _) {
            return "";
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    // ------------------------------------------------------------------ jobs

    /** A parsed job line: its type and its {@code key=value} parameters. */
    private record Spec(String line, String type, Map<String, String> params) {
        static Spec parse(String line) {
            final List<String> parts = WHITESPACE.splitAsStream(line.trim()).toList();
            final Map<String, String> params = new LinkedHashMap<>();
            for (String part : parts.subList(1, parts.size())) {
                final int eq = part.indexOf('=');
                params.put(part.substring(0, eq), part.substring(eq + 1));
            }
            return new Spec(line, parts.getFirst(), params);
        }

        int integer(String key, int fallback) {
            final String value = params.get(key);
            return value == null ? fallback : Integer.parseInt(value);
        }

        String string(String key, String fallback) {
            return params.getOrDefault(key, fallback);
        }
    }

    private static JsonObject runJob(String line) throws Exception {
        final Spec spec = Spec.parse(line);
        final double loadBefore = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        final Bench bench = new Bench(spec);
        final JsonObject result = switch (spec.type()) {
            case "packet" -> bench.packet();
            case "relight" -> bench.relight();
            case "local" -> bench.local();
            default -> throw new IllegalArgumentException("Unknown job type: " + spec.type());
        };
        result.addProperty("job", line);
        // Timings are only as good as the machine was idle: the 1-minute load average, before and after
        result.addProperty("loadAverageBefore", loadBefore);
        result.addProperty("loadAverageAfter", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        final JsonObject jvm = new JsonObject();
        jvm.addProperty("maxHeapMiB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        final JsonArray inputArgs = new JsonArray();
        ManagementFactory.getRuntimeMXBean().getInputArguments().forEach(inputArgs::add);
        jvm.add("inputArguments", inputArgs);
        result.add("jvm", jvm);
        return result;
    }

    /** One job running in a child JVM: one process, one dimension type, fresh worlds per round. */
    private static final class Bench {
        private final Spec spec;
        private final int height;
        private final int minY;
        private final SyntheticTerrain.Kind kind;
        private final boolean optimize;
        private final ServerProcess process;
        private final RegistryKey<DimensionType> dimension;

        Bench(Spec spec) {
            this.spec = spec;
            this.height = spec.integer("h", 384);
            // 384 keeps the vanilla overworld layout; the lower worlds start at 0 like the host's managed instance world
            this.minY = height == 384 ? -64 : 0;
            this.kind = SyntheticTerrain.Kind.valueOf(spec.string("t", "natural").toUpperCase(Locale.ROOT));
            this.optimize = spec.string("p", "opt").equals("opt");
            this.process = ServerProcess.of();
            this.process.exception().setExceptionHandler(e -> {
                e.printStackTrace();
                Runtime.getRuntime().halt(3);
            });
            this.dimension = process.dimensionType().register(Key.key("synaxis:bench_" + height),
                    DimensionType.builder().minY(minY).height(height).logicalHeight(height).build());
        }

        private SyntheticTerrain terrain() {
            return new SyntheticTerrain(kind, minY, height);
        }

        private InstanceContainer world(boolean lighting) {
            final InstanceContainer instance = new InstanceContainer(process, UUID.randomUUID(), dimension);
            if (lighting) instance.setChunkSupplier(LightingChunk::new);
            instance.setGenerator(terrain());
            return instance;
        }

        /** Loads a grid x grid square starting at chunk 0,0 and returns its chunks in row order. */
        private List<Chunk> load(InstanceContainer instance, int grid) {
            final List<CompletableFuture<Chunk>> futures = new ArrayList<>(grid * grid);
            for (int z = 0; z < grid; z++) {
                for (int x = 0; x < grid; x++) {
                    futures.add(instance.loadChunk(x, z));
                }
            }
            final List<Chunk> chunks = new ArrayList<>(futures.size());
            for (CompletableFuture<Chunk> future : futures) chunks.add(future.join());
            if (optimize) {
                // A loader that decodes the network format (.synx, Anvil) ends with indirect palettes;
                // setAll leaves every mixed section direct (15 bits per block)
                for (Chunk chunk : chunks) {
                    for (Section section : chunk.getSections()) section.blockPalette().optimize(Palette.Optimization.SIZE);
                }
            }
            return chunks;
        }

        // -------------------------------------------------------------- packet

        JsonObject packet() {
            final int grid = spec.integer("grid", 32);
            final int rounds = spec.integer("rounds", 3);
            final int warmup = spec.integer("warmup", 1);
            final Samples plainMs = new Samples();
            final Samples plainBytes = new Samples();
            final Samples plainRawBytes = new Samples();
            final Samples lazyLightMs = new Samples();
            final Samples litMs = new Samples();
            final Samples litBytes = new Samples();
            final Samples litRawBytes = new Samples();
            // Byte-exact reproducibility: the same job must give the same digests in every JVM
            final MessageDigest plainDigest = sha256();
            final MessageDigest plainRawDigest = sha256();
            final MessageDigest litDigest = sha256();
            final MessageDigest litRawDigest = sha256();
            final Samples skyArraysSent = new Samples();
            final Samples blockArraysSent = new Samples();
            final Map<String, Samples> heap = new LinkedHashMap<>();
            final Map<String, Samples> pass = new LinkedHashMap<>();
            final JsonArray roundsJson = new JsonArray();
            JsonObject breakdown = null;

            for (int round = 0; round < warmup + rounds; round++) {
                final boolean measured = round >= warmup;
                final JsonObject roundJson = new JsonObject();

                // DynamicChunk: the engine default, no light at all
                long base = liveHeap();
                InstanceContainer instance = world(false);
                List<Chunk> chunks = load(instance, grid);
                final long plainGen = liveHeap() - base;
                long cpu = processCpuNanos();
                long wall = System.nanoTime();
                for (Chunk chunk : chunks) {
                    final Serialized s = serialize(chunk);
                    if (measured && interior(chunk, grid)) {
                        plainMs.add(s.millis());
                        plainBytes.add(s.framedBytes());
                    }
                }
                final double plainWall = (System.nanoTime() - wall) / 1e6;
                final double plainCpu = (processCpuNanos() - cpu) / 1e6;
                for (Chunk chunk : chunks) {
                    if (measured && interior(chunk, grid)) {
                        final byte[] raw = uncompressedBytes(chunk);
                        plainRawBytes.add(raw.length);
                        plainRawDigest.update(raw);
                        plainDigest.update(framed(chunk));
                    }
                }
                final long plainCached = liveHeap() - base;
                chunks = null;
                instance = null;

                // LightingChunk, light computed lazily by the first packet, as when a player joins
                base = liveHeap();
                instance = world(true);
                chunks = load(instance, grid);
                final long litGen = liveHeap() - base;
                cpu = processCpuNanos();
                wall = System.nanoTime();
                for (Chunk chunk : chunks) {
                    final Serialized s = serialize(chunk);
                    if (measured && interior(chunk, grid)) lazyLightMs.add(s.millis());
                }
                final double lazyWall = (System.nanoTime() - wall) / 1e6;
                final double lazyCpu = (processCpuNanos() - cpu) / 1e6;
                final long litCached = liveHeap() - base;
                for (Chunk chunk : chunks) chunk.invalidate(); // drops the packet caches, keeps the light arrays
                final long litNoCache = liveHeap() - base;
                // Light is known now: this pass costs what a baked-light encoder would
                cpu = processCpuNanos();
                wall = System.nanoTime();
                for (Chunk chunk : chunks) {
                    final Serialized s = serialize(chunk);
                    if (measured && interior(chunk, grid)) {
                        litMs.add(s.millis());
                        litBytes.add(s.framedBytes());
                        skyArraysSent.add(s.skyArrays());
                        blockArraysSent.add(s.blockArrays());
                    }
                }
                final double litWall = (System.nanoTime() - wall) / 1e6;
                final double litCpu = (processCpuNanos() - cpu) / 1e6;
                for (Chunk chunk : chunks) {
                    if (measured && interior(chunk, grid)) {
                        final byte[] raw = uncompressedBytes(chunk);
                        litRawBytes.add(raw.length);
                        litRawDigest.update(raw);
                        litDigest.update(framed(chunk));
                    }
                }
                if (measured) {
                    final double n = chunks.size();
                    record(pass, "plainWall", plainWall / n);
                    record(pass, "plainCpu", plainCpu / n);
                    record(pass, "lazyLightWall", lazyWall / n);
                    record(pass, "lazyLightCpu", lazyCpu / n);
                    record(pass, "litWall", litWall / n);
                    record(pass, "litCpu", litCpu / n);
                    roundJson.addProperty("plainPassWallMs", plainWall);
                    roundJson.addProperty("plainPassCpuMs", plainCpu);
                    roundJson.addProperty("lazyLightPassWallMs", lazyWall);
                    roundJson.addProperty("lazyLightPassCpuMs", lazyCpu);
                    roundJson.addProperty("litPassWallMs", litWall);
                    roundJson.addProperty("litPassCpuMs", litCpu);
                    record(heap, "plainGenerated", plainGen / 1024.0 / n);
                    record(heap, "plainWithPacketCache", plainCached / 1024.0 / n);
                    record(heap, "lightingGenerated", litGen / 1024.0 / n);
                    record(heap, "lightingLit", litNoCache / 1024.0 / n);
                    record(heap, "lightingLitWithPacketCache", litCached / 1024.0 / n);
                    roundJson.addProperty("plainGeneratedKiBPerChunk", plainGen / 1024.0 / n);
                    roundJson.addProperty("lightingLitKiBPerChunk", litNoCache / 1024.0 / n);
                    roundJson.addProperty("lightingLitWithPacketCacheKiBPerChunk", litCached / 1024.0 / n);
                    roundsJson.add(roundJson);
                    breakdown = breakdown(chunks);
                }
                chunks = null;
                instance = null;
            }

            final JsonObject result = new JsonObject();
            result.add("params", params(grid * grid, rounds, warmup));
            result.addProperty("sampleChunks", "interior " + (grid - 2) + "x" + (grid - 2) + " of each round, pooled over rounds");
            result.add("plainFirstSerializeMs", plainMs.json());
            result.add("plainFramedBytes", plainBytes.json());
            result.add("plainUncompressedBytes", plainRawBytes.json());
            result.add("lazyLightFirstSerializeMs", lazyLightMs.json());
            result.add("litSerializeMs", litMs.json());
            result.add("litFramedBytes", litBytes.json());
            result.add("litUncompressedBytes", litRawBytes.json());
            result.addProperty("plainFramedSha256", HexFormat.of().formatHex(plainDigest.digest()));
            result.addProperty("plainUncompressedSha256", HexFormat.of().formatHex(plainRawDigest.digest()));
            result.addProperty("litFramedSha256", HexFormat.of().formatHex(litDigest.digest()));
            result.addProperty("litUncompressedSha256", HexFormat.of().formatHex(litRawDigest.digest()));
            result.add("skyArraysPerPacket", skyArraysSent.json());
            result.add("blockArraysPerPacket", blockArraysSent.json());
            final JsonObject heapJson = new JsonObject();
            heap.forEach((key, samples) -> heapJson.add(key + "KiBPerChunk", samples.json()));
            result.add("heap", heapJson);
            final JsonObject passJson = new JsonObject();
            pass.forEach((key, samples) -> passJson.add(key + "MsPerChunk", samples.json()));
            result.add("wholePass", passJson);
            result.add("breakdown", breakdown);
            result.add("rounds", roundsJson);
            return result;
        }

        private static boolean interior(Chunk chunk, int grid) {
            return chunk.getChunkX() > 0 && chunk.getChunkX() < grid - 1
                    && chunk.getChunkZ() > 0 && chunk.getChunkZ() < grid - 1;
        }

        private record Serialized(double millis, int framedBytes, int skyArrays, int blockArrays) {}

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Frames and compresses the chunk packet the way a send does, timing only that. */
        private Serialized serialize(Chunk chunk) {
            final CachedPacket cached = (CachedPacket) chunk.getFullDataPacket();
            final long start = System.nanoTime();
            final NetworkBuffer body = cached.body(ConnectionState.PLAY, process, COMPRESSION_THRESHOLD);
            final double millis = (System.nanoTime() - start) / 1e6;
            final ChunkDataPacket packet = (ChunkDataPacket) cached.packet();
            return new Serialized(millis, (int) Objects.requireNonNull(body).readableBytes(),
                    packet.lightData().skyLight().size(), packet.lightData().blockLight().size());
        }

        /** The framed, compressed bytes a send writes, from the cache the timed pass filled. */
        private byte[] framed(Chunk chunk) {
            final NetworkBuffer body = Objects.requireNonNull(((CachedPacket) chunk.getFullDataPacket())
                    .body(ConnectionState.PLAY, process, COMPRESSION_THRESHOLD));
            final byte[] bytes = new byte[(int) body.readableBytes()];
            body.copyTo(body.readIndex(), bytes, 0, bytes.length);
            return bytes;
        }

        /** The packet body before compression, from the packet the cache already holds. */
        private byte[] uncompressedBytes(Chunk chunk) {
            final ChunkDataPacket packet = (ChunkDataPacket) ((CachedPacket) chunk.getFullDataPacket()).packet();
            return NetworkBuffer.makeArray(ChunkDataPacket.SERIALIZER, packet, process);
        }

        // -------------------------------------------------------------- relight

        JsonObject relight() {
            final int chunkCount = spec.integer("n", 4096);
            final int grid = (int) Math.round(Math.sqrt(chunkCount));
            if (grid * grid != chunkCount) throw new IllegalArgumentException("n must be a square: " + chunkCount);
            final int rounds = spec.integer("rounds", 3);

            // Warm-up: one 32x32 world lit and discarded, so the measured rounds run compiled code
            final int warmupGrid = Math.min(grid, 32);
            InstanceContainer instance = world(true);
            LightingChunk.relight(instance, load(instance, warmupGrid));
            instance = null;

            final Samples relightMs = new Samples();
            final Samples relightCpuMs = new Samples();
            final Samples generatedKiB = new Samples();
            final Samples relightDeltaMiB = new Samples();
            final Samples totalKiB = new Samples();
            final JsonArray roundsJson = new JsonArray();
            JsonObject breakdown = null;
            for (int round = 0; round < rounds; round++) {
                final long base = liveHeap();
                instance = world(true);
                final long loadStart = System.nanoTime();
                final List<Chunk> chunks = load(instance, grid);
                final double loadMs = (System.nanoTime() - loadStart) / 1e6;
                final long generated = liveHeap();
                final long cpu = processCpuNanos();
                final long start = System.nanoTime();
                LightingChunk.relight(instance, chunks);
                final double ms = (System.nanoTime() - start) / 1e6;
                final double cpuMs = (processCpuNanos() - cpu) / 1e6;
                final long lit = liveHeap();
                relightMs.add(ms);
                relightCpuMs.add(cpuMs);
                generatedKiB.add((generated - base) / 1024.0 / chunkCount);
                relightDeltaMiB.add((lit - generated) / (1024.0 * 1024));
                totalKiB.add((lit - base) / 1024.0 / chunkCount);
                final JsonObject roundJson = new JsonObject();
                roundJson.addProperty("loadMs", loadMs);
                roundJson.addProperty("relightMs", ms);
                roundJson.addProperty("relightCpuMs", cpuMs);
                roundJson.addProperty("generatedMiB", (generated - base) / (1024.0 * 1024));
                roundJson.addProperty("relightDeltaMiB", (lit - generated) / (1024.0 * 1024));
                roundsJson.add(roundJson);
                if (round == rounds - 1) breakdown = breakdown(chunks);
                instance = null;
            }
            final JsonObject result = new JsonObject();
            result.add("params", params(chunkCount, rounds, 1));
            result.add("relightWallMs", relightMs.json());
            result.add("relightCpuMs", relightCpuMs.json());
            result.add("generatedKiBPerChunk", generatedKiB.json());
            result.add("relightHeapDeltaMiB", relightDeltaMiB.json());
            result.add("litTotalKiBPerChunk", totalKiB.json());
            result.add("breakdown", breakdown);
            result.add("rounds", roundsJson);
            return result;
        }

        // -------------------------------------------------------------- local

        JsonObject local() {
            final int grid = spec.integer("grid", 32);
            final int samples = spec.integer("samples", 300);
            final int warmupSamples = spec.integer("warmupSamples", 60);
            final InstanceContainer instance = world(true);
            final List<Chunk> chunks = load(instance, grid);
            LightingChunk.relight(instance, chunks);
            for (Chunk chunk : chunks) serialize(chunk); // steady state: every chunk has been sent once

            final String[] kinds = {"placeGlowstone", "placeStone", "dig"};
            final Map<String, Samples> lazy = new LinkedHashMap<>();
            final Map<String, Samples> full = new LinkedHashMap<>();
            final Samples lazyAll = new Samples();
            final Samples fullAll = new Samples();
            final Random random = new Random(42);
            for (int i = 0; i < warmupSamples + samples; i++) {
                final boolean measured = i >= warmupSamples;
                final String change = kinds[i % kinds.length];

                // A: the lazy path. The block change invalidates the 3x3x3 sections around it; building
                // the full light data of the 3x3 chunks recomputes exactly those (what a new observer's
                // chunk packet would trigger, minus encoding)
                final Chunk a = change(instance, random, grid, change);
                long start = System.nanoTime();
                for (Chunk neighbour : neighbours(instance, a)) ((LightingChunk) neighbour).createLightData(true);
                final double lazyMs = (System.nanoTime() - start) / 1e6;

                // B: relight(instance, 3x3 chunks), every section of the 9 chunks from scratch
                final Chunk b = change(instance, random, grid, change);
                start = System.nanoTime();
                LightingChunk.relight(instance, neighbours(instance, b));
                final double fullMs = (System.nanoTime() - start) / 1e6;

                if (measured) {
                    lazy.computeIfAbsent(change, _ -> new Samples()).add(lazyMs);
                    full.computeIfAbsent(change, _ -> new Samples()).add(fullMs);
                    lazyAll.add(lazyMs);
                    fullAll.add(fullMs);
                }
            }
            final JsonObject result = new JsonObject();
            result.add("params", params(grid * grid, 1, warmupSamples));
            result.addProperty("samples", samples);
            final JsonObject lazyJson = new JsonObject();
            lazyJson.add("all", lazyAll.json());
            lazy.forEach((key, value) -> lazyJson.add(key, value.json()));
            result.add("lazy3x3Ms", lazyJson);
            final JsonObject fullJson = new JsonObject();
            fullJson.add("all", fullAll.json());
            full.forEach((key, value) -> fullJson.add(key, value.json()));
            result.add("relight3x3Ms", fullJson);
            return result;
        }

        /** Applies one change at the surface of a random interior column and returns its chunk. */
        private Chunk change(InstanceContainer instance, Random random, int grid, String change) {
            final int chunkX = 2 + random.nextInt(grid - 4);
            final int chunkZ = 2 + random.nextInt(grid - 4);
            final int x = chunkX * 16 + random.nextInt(16);
            final int z = chunkZ * 16 + random.nextInt(16);
            int y = minY + height - 1;
            while (y > minY && instance.getBlock(x, y, z).air()) y--;
            switch (change) {
                case "placeGlowstone" -> instance.setBlock(x, y + 1, z, Block.GLOWSTONE);
                case "placeStone" -> instance.setBlock(x, y + 1, z, Block.STONE);
                default -> instance.setBlock(x, y, z, Block.AIR);
            }
            return Objects.requireNonNull(instance.getChunk(chunkX, chunkZ));
        }

        private static List<Chunk> neighbours(Instance instance, Chunk center) {
            final List<Chunk> result = new ArrayList<>(9);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    result.add(instance.getChunk(center.getChunkX() + dx, center.getChunkZ() + dz));
                }
            }
            return result;
        }

        // -------------------------------------------------------------- shared

        private JsonObject params(int chunks, int rounds, int warmup) {
            final JsonObject params = new JsonObject();
            params.addProperty("type", spec.type());
            params.addProperty("height", height);
            params.addProperty("minY", minY);
            params.addProperty("terrain", kind.name().toLowerCase(Locale.ROOT));
            params.addProperty("palettes", optimize ? "optimized (indirect, as decoded from the network format)" : "as generated by setAll (direct)");
            params.addProperty("chunks", chunks);
            params.addProperty("rounds", rounds);
            params.addProperty("warmup", warmup);
            params.addProperty("compressionThreshold", COMPRESSION_THRESHOLD);
            return params;
        }

        private static void record(Map<String, Samples> map, String key, double value) {
            map.computeIfAbsent(key, _ -> new Samples()).add(value);
        }

        /**
         * What the chunks hold, counted rather than inferred: sections by palette kind, and the light
         * arrays the sections keep (content, propagation, propagation swap), shared sentinels excluded.
         */
        private static JsonObject breakdown(List<Chunk> chunks) {
            long single = 0;
            long indirect = 0;
            long direct = 0;
            long paletteBytes = 0;
            final Set<byte[]> skyArrays = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<byte[]> blockArrays = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Chunk chunk : chunks) {
                for (Section section : chunk.getSections()) {
                    final Palette palette = section.blockPalette();
                    final int bits = palette.bitsPerEntry();
                    if (bits == 0) {
                        single++;
                    } else {
                        if (bits > Palette.BLOCK_PALETTE_MAX_BITS) direct++;
                        else indirect++;
                        paletteBytes += 16L + 8L * ((4096 + (64 / bits) - 1) / (64 / bits));
                    }
                    collectArrays(section.skyLight(), skyArrays);
                    collectArrays(section.blockLight(), blockArrays);
                }
            }
            final double n = chunks.size();
            final JsonObject json = new JsonObject();
            json.addProperty("chunks", chunks.size());
            json.addProperty("sectionsPerChunk", chunks.getFirst().getSections().size());
            json.addProperty("singleValueSectionsPerChunk", single / n);
            json.addProperty("indirectSectionsPerChunk", indirect / n);
            json.addProperty("directSectionsPerChunk", direct / n);
            json.addProperty("paletteValuesKiBPerChunk", paletteBytes / 1024.0 / n);
            json.addProperty("skyLightArraysPerChunk", skyArrays.size() / n);
            json.addProperty("blockLightArraysPerChunk", blockArrays.size() / n);
            json.addProperty("lightArraysKiBPerChunk", (skyArrays.size() + blockArrays.size()) * (double) LIGHT_ARRAY_BYTES / 1024.0 / n);
            return json;
        }

        private static final String[] LIGHT_FIELDS = {"content", "contentPropagation", "contentPropagationSwap"};

        private static void collectArrays(Light light, Set<byte[]> into) {
            for (String name : LIGHT_FIELDS) {
                try {
                    final Field field = light.getClass().getDeclaredField(name);
                    field.setAccessible(true);
                    if (field.get(light) instanceof byte[] array && array.length > 0
                            && array != LightCompute.EMPTY_CONTENT && array != LightCompute.CONTENT_FULLY_LIT) {
                        into.add(array);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Light layout changed: " + light.getClass() + "." + name, e);
                }
            }
        }
    }

    /**
     * Heap that survives two full collections. Fails loudly if no collection ran, because then the
     * number would measure allocation instead of retention.
     */
    private static long liveHeap() {
        final long before = collections();
        System.gc();
        System.gc();
        if (collections() == before) throw new IllegalStateException("System.gc() did not collect; drop -XX:+DisableExplicitGC");
        final Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long collections() {
        long total = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, collector.getCollectionCount());
        }
        return total;
    }

    private static long processCpuNanos() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return 0;
    }

    /** A growable list of samples with nearest-rank percentiles. */
    private static final class Samples {
        private double[] values = new double[64];
        private int size;

        void add(double value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        JsonObject json() {
            final JsonObject json = new JsonObject();
            json.addProperty("n", size);
            if (size == 0) return json;
            final double[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            double sum = 0;
            for (double value : sorted) sum += value;
            json.addProperty("median", percentile(sorted, 0.50));
            json.addProperty("p95", percentile(sorted, 0.95));
            json.addProperty("mean", sum / size);
            json.addProperty("min", sorted[0]);
            json.addProperty("max", sorted[size - 1]);
            return json;
        }

        private static double percentile(double[] sorted, double p) {
            final int rank = (int) Math.ceil(p * sorted.length);
            return sorted[Math.max(0, rank - 1)];
        }
    }
}
