package io.github.bearisbug.synaxis.baker;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.baker.anvil.AnvilSourceException;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxBuildVerifier;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxExpectations;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxRegion;
import io.github.bearisbug.synaxis.synx.SynxRegionWriter;
import io.github.bearisbug.synaxis.synx.SynxSettings;
import io.github.bearisbug.synaxis.synx.SynxSlot;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@code bake} of API-M3-003 and the {@code ENT-MapBuild} life cycle of §11.2 up to {@code published} or
 * {@code failed}: gate the source, bake every region in parallel with a one chunk light ring, derive the
 * {@code content_hash} and {@code build_id}, write the regions and the manifest, verify, and move the build
 * directory into place.
 * <p>
 * Everything is written under {@code <maps_root>/<map_id>/.baking-*}, on the file system of the release, so
 * the last step is one atomic rename. A bake that fails in a region or in the verification leaves that
 * directory behind for inspection (§11.2); every other refusal leaves nothing.
 */
public final class Baker {
    /** {@code baker_version}: raised by every change to what the baker writes for the same input. */
    public static final int VERSION = 1;
    public static final int COMPRESSION_THRESHOLD = 256;
    public static final int COMPRESSION_LEVEL = -1;

    public static final int EXIT_PUBLISHED = 0;
    public static final int EXIT_SOURCE = 2;
    public static final int EXIT_REGION = 3;
    public static final int EXIT_VERIFY = 4;
    public static final int EXIT_EXISTS = 5;
    public static final int EXIT_LIGHT_VERSION = 6;

    /**
     * What to bake and where.
     *
     * @param handlers              the handler of a namespace, null when it has none; segment ① carries only its tags
     * @param verifiedLightVersions {@link LightVersions#verified()} outside of tests
     * @param samplesPerRegion      chunks per region the verification bakes again and compares byte for byte
     */
    public record Request(String mapId, BakeSource source, Path mapsRoot, int threads, Path logDir,
                          Registries registries, Function<String, @Nullable BlockHandler> handlers,
                          Set<Integer> verifiedLightVersions, int samplesPerRegion) {
        public Request {
            if (threads < 1) throw new IllegalArgumentException("threads >= 1: " + threads);
            if (samplesPerRegion < 1) throw new IllegalArgumentException("samples per region >= 1: " + samplesPerRegion);
            verifiedLightVersions = Set.copyOf(verifiedLightVersions);
        }
    }

    /**
     * How a bake ended.
     *
     * @param directory the published build, or the {@code .baking-*} directory kept after exit code 3 or 4
     * @param manifest  what was written, null when the bake stopped before a manifest existed
     */
    public record Result(int exitCode, @Nullable Path directory, @Nullable SynxManifest manifest, String message) {
    }

    private record RegionOutcome(int regionX, int regionZ, SynxRegionWriter.Encoded encoded, long sourceLightDiff) {
    }

    private record Verification(int samples, int diffs, @Nullable String problem) {
    }

    private final Request request;
    private final BakeSource source;
    private final SynxSettings settings;
    private final BakeEvents events;

    private Baker(Request request) {
        this.request = request;
        this.source = request.source();
        this.settings = new SynxSettings(MinecraftServer.PROTOCOL_VERSION, COMPRESSION_THRESHOLD, COMPRESSION_LEVEL,
                source.minY(), source.height(), SynxDigests.registryDigest(request.registries()), LightEngine.VERSION,
                VERSION, true);
        this.events = new BakeEvents(request.logDir());
    }

    public static Result bake(Request request) {
        if (!request.verifiedLightVersions().contains(LightEngine.VERSION)) {
            return new Result(EXIT_LIGHT_VERSION, null, null, "LightEngine.VERSION " + LightEngine.VERSION
                    + " has not passed the light parity test (verified: " + request.verifiedLightVersions() + ")");
        }
        try {
            request.source().checkGate(request.threads());
        } catch (IOException e) {
            return new Result(EXIT_SOURCE, null, null, e instanceof AnvilSourceException ? e.getMessage()
                    : "the source cannot be read: " + e.getMessage());
        }
        final ExecutorService executor = Executors.newFixedThreadPool(request.threads());
        try {
            return new Baker(request).run(executor);
        } finally {
            executor.shutdownNow();
        }
    }

    private Result run(ExecutorService executor) {
        final JsonObject started = new JsonObject();
        started.addProperty("map_id", request.mapId());
        started.addProperty("source", source.kind());
        if (source.dataVersion() != null) started.addProperty("source_data_version", source.dataVersion());
        started.addProperty("threads", request.threads());
        started.addProperty("baker_version", VERSION);
        started.addProperty("light_engine_version", LightEngine.VERSION);
        events.record("map_build_started", started);

        final Instant startedAt = Instant.now();
        final long wallStart = System.nanoTime();
        final long cpuStart = processCpuNanos();
        final Path mapDir = request.mapsRoot().resolve(request.mapId());
        final Path work;
        try {
            Files.createDirectories(mapDir);
            work = Files.createTempDirectory(mapDir, ".baking-");
            Files.createDirectory(work.resolve("staging"));
        } catch (IOException e) {
            return finish(new Result(EXIT_REGION, null, null, "cannot create the work directory: " + e), null, 0, 0);
        }

        // baking
        final List<int[]> regions = regions(source.bounds());
        final List<Future<RegionOutcome>> tasks = new ArrayList<>(regions.size());
        for (int[] region : regions) {
            tasks.add(executor.submit(() -> bakeRegion(region[0], region[1], work.resolve("staging"))));
        }
        final List<RegionOutcome> outcomes = new ArrayList<>(regions.size());
        final List<String> failures = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            try {
                outcomes.add(tasks.get(i).get());
            } catch (ExecutionException e) {
                failures.add("region " + regions.get(i)[0] + ", " + regions.get(i)[1] + ": " + e.getCause());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                failures.add("interrupted");
                break;
            }
        }
        if (!failures.isEmpty()) {
            return finish(new Result(EXIT_REGION, work, null, failures.size() + " region(s) failed; first "
                    + failures.getFirst()), null, failures.size(), 0);
        }

        // baking -> verifying: content hash, region files, manifest draft
        final List<SynxDigests.RegionHash> hashes = new ArrayList<>(outcomes.size());
        long sourceLightDiff = 0;
        for (RegionOutcome outcome : outcomes) {
            hashes.add(new SynxDigests.RegionHash(outcome.regionX(), outcome.regionZ(), outcome.encoded().regionContentHash()));
            sourceLightDiff += outcome.sourceLightDiff();
        }
        final byte[] contentHash = SynxDigests.contentHash(hashes);
        final String buildId = SynxManifest.buildId(request.mapId(), contentHash, settings.protocolVersion());
        final Path buildDir = work.resolve(buildId);
        final List<SynxManifest.Region> manifestRegions = new ArrayList<>(outcomes.size());
        long bytes = 0;
        try {
            Files.createDirectories(buildDir.resolve("regions"));
            for (RegionOutcome outcome : outcomes) {
                final SynxRegionWriter.Encoded encoded = outcome.encoded();
                encoded.write(buildDir.resolve("regions").resolve(SynxFormat.regionFileName(outcome.regionX(),
                        outcome.regionZ())), contentHash);
                manifestRegions.add(new SynxManifest.Region(outcome.regionX(), outcome.regionZ(), encoded.fileSize(),
                        encoded.payloadSha256(), encoded.chunkCount()));
                bytes += encoded.fileSize();
            }
            Files.delete(work.resolve("staging"));
        } catch (IOException e) {
            return finish(new Result(EXIT_REGION, work, null, "cannot write the regions: " + e), buildId, 0, 0);
        }
        final boolean anvil = source.dataVersion() != null;
        SynxManifest manifest = manifest(buildId, contentHash, manifestRegions, startedAt, wallStart, cpuStart,
                new SynxManifest.Verify(0, 0, anvil ? sourceLightDiff : null));
        try {
            writeManifest(buildDir, manifest);
        } catch (IOException e) {
            return finish(new Result(EXIT_REGION, work, manifest, "cannot write the manifest: " + e), buildId, 0, 0);
        }

        // verifying
        final Verification verification = verify(buildDir, manifest, outcomes, executor);
        manifest = manifest(buildId, contentHash, manifestRegions, startedAt, wallStart, cpuStart,
                new SynxManifest.Verify(verification.samples(), verification.diffs(), anvil ? sourceLightDiff : null));
        try {
            writeManifest(buildDir, manifest);
        } catch (IOException e) {
            return finish(new Result(EXIT_VERIFY, work, manifest, "cannot write the manifest: " + e), buildId, 0, 0);
        }
        if (verification.problem() != null || verification.diffs() != 0) {
            return finish(new Result(EXIT_VERIFY, work, manifest, verification.problem() != null ? verification.problem()
                    : verification.diffs() + " of " + verification.samples() + " sampled chunks differ"), buildId, 0,
                    verification.diffs());
        }

        // verifying -> published
        final Path target = mapDir.resolve(buildId);
        try {
            if (Files.exists(target)) {
                deleteTree(work);
                return finish(new Result(EXIT_EXISTS, null, manifest, target + " already exists"), buildId, 0, 0);
            }
            Files.move(buildDir, target, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(work);
        } catch (IOException e) {
            return finish(new Result(EXIT_VERIFY, work, manifest, "cannot publish " + target + ": " + e), buildId, 0, 0);
        }
        return finish(new Result(EXIT_PUBLISHED, target, manifest, "published " + target + ", " + bytes + " bytes"),
                buildId, 0, 0);
    }

    private Result finish(Result result, @Nullable String buildId, int failedRegions, int verifyDiffs) {
        final SynxManifest manifest = result.manifest();
        final JsonObject finished = new JsonObject();
        if (buildId != null) finished.addProperty("build_id", buildId);
        finished.addProperty("outcome", result.exitCode() == EXIT_PUBLISHED ? "published" : "failed");
        if (manifest != null) {
            finished.addProperty("wall_ms", manifest.bake().wallMs());
            finished.addProperty("cpu_ms", manifest.bake().cpuMs());
            finished.addProperty("bytes", manifest.regions().stream().mapToLong(SynxManifest.Region::bytes).sum());
            finished.addProperty("farview_bytes", 0);
            if (manifest.verify().sourceLightDiff() != null) {
                finished.addProperty("source_light_diff", manifest.verify().sourceLightDiff());
            }
        }
        finished.addProperty("failed_regions", failedRegions);
        finished.addProperty("verify_diffs", verifyDiffs);
        events.record("map_build_finished", finished);
        return result;
    }

    private SynxManifest manifest(String buildId, byte[] contentHash, List<SynxManifest.Region> regions,
                                  Instant startedAt, long wallStart, long cpuStart, SynxManifest.Verify verify) {
        return new SynxManifest(request.mapId(), buildId, contentHash, MinecraftServer.VERSION_NAME,
                settings.protocolVersion(), settings.registryDigest(), LightEngine.VERSION, VERSION,
                new SynxManifest.World(source.minY(), source.height()), source.bounds(), regions, List.of(),
                new SynxManifest.Source(source.kind(), source.dataVersion(), source.lightCoverage()),
                new SynxManifest.Bake(startedAt.toString(), (System.nanoTime() - wallStart) / 1_000_000,
                        (processCpuNanos() - cpuStart) / 1_000_000, request.threads(), Runtime.version().toString()),
                verify);
    }

    private static void writeManifest(Path buildDir, SynxManifest manifest) throws IOException {
        Files.writeString(buildDir.resolve(SynxManifest.FILE_NAME), manifest.toJson());
    }

    /** The regions overlapping the bounds, in (rx, rz) order. */
    private static List<int[]> regions(SynxManifest.Bounds bounds) {
        final List<int[]> regions = new ArrayList<>();
        for (int rx = SynxFormat.region(bounds.minX()); rx <= SynxFormat.region(bounds.maxX()); rx++) {
            for (int rz = SynxFormat.region(bounds.minZ()); rz <= SynxFormat.region(bounds.maxZ()); rz++) {
                regions.add(new int[]{rx, rz});
            }
        }
        return regions;
    }

    /**
     * One region: its chunks inside the bounds plus the ring around them that lies inside the bounds, lit in one
     * computation, encoded in index order into a staging file.
     */
    private RegionOutcome bakeRegion(int regionX, int regionZ, Path staging) throws IOException {
        final SynxManifest.Bounds bounds = source.bounds();
        final int minX = Math.max(regionX << SynxFormat.REGION_SHIFT, bounds.minX());
        final int minZ = Math.max(regionZ << SynxFormat.REGION_SHIFT, bounds.minZ());
        final int maxX = Math.min((regionX << SynxFormat.REGION_SHIFT) + SynxFormat.CHUNKS_PER_SIDE - 1, bounds.maxX());
        final int maxZ = Math.min((regionZ << SynxFormat.REGION_SHIFT) + SynxFormat.CHUNKS_PER_SIDE - 1, bounds.maxZ());
        final AreaView view = read(minX, minZ, maxX, maxZ);
        final LightResult light = LightEngine.computeArea(view, minX, minZ, maxX, maxZ);

        final SynxRegionWriter writer = new SynxRegionWriter(regionX, regionZ, settings);
        long sourceLightDiff = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final SourceChunk chunk = view.chunk(x, z);
                assert chunk != null;
                writer.add(ChunkEncoder.encode(chunk, light, source.minY(), settings, request.registries(), request.handlers()));
                sourceLightDiff += ChunkEncoder.sourceLightDiff(chunk, light, source.minY());
            }
        }
        final Path file = staging.resolve(SynxFormat.regionFileName(regionX, regionZ) + ".part");
        return new RegionOutcome(regionX, regionZ, writer.encode(file), sourceLightDiff);
    }

    /** Reads the rectangle and the ring of one chunk around it, where the ring is inside the bounds. */
    private AreaView read(int minX, int minZ, int maxX, int maxZ) throws IOException {
        final SynxManifest.Bounds bounds = source.bounds();
        final int originX = minX - 1, originZ = minZ - 1;
        final int width = maxX - minX + 3, depth = maxZ - minZ + 3;
        final SourceChunk[] chunks = new SourceChunk[width * depth];
        for (int x = Math.max(originX, bounds.minX()); x <= Math.min(maxX + 1, bounds.maxX()); x++) {
            for (int z = Math.max(originZ, bounds.minZ()); z <= Math.min(maxZ + 1, bounds.maxZ()); z++) {
                chunks[(x - originX) * depth + (z - originZ)] = source.read(x, z);
            }
        }
        return new AreaView(source.minY(), source.height(), originX, originZ, width, depth, chunks);
    }

    /**
     * {@code verify} of API-M3-003. Digests: the start-up check a shard runs ({@link SynxBuildVerifier}), every
     * {@code region_content_hash} recomputed from the written records, and a record for every chunk inside the
     * bounds. Samples: chunks picked from each region's content hash, baked again on their own with just their
     * 3×3 neighbourhood and compared with the written record, segment ① byte for byte.
     */
    private Verification verify(Path buildDir, SynxManifest manifest, List<RegionOutcome> outcomes,
                                ExecutorService executor) {
        try {
            SynxBuildVerifier.verify(buildDir, SynxExpectations.of(request.registries(), COMPRESSION_THRESHOLD,
                    source.minY(), source.height(), LightEngine.VERSION), manifest.bounds());
        } catch (IOException | SynxException e) {
            return new Verification(0, 0, "digest check: " + e.getMessage());
        }
        final List<Callable<Integer>> samples = new ArrayList<>();
        final SynxManifest.Bounds bounds = source.bounds();
        try (Arena arena = Arena.ofShared()) {
            for (RegionOutcome outcome : outcomes) {
                final String name = SynxFormat.regionFileName(outcome.regionX(), outcome.regionZ());
                final SynxRegion region = SynxRegion.open(buildDir.resolve("regions").resolve(name), arena);
                if (!Arrays.equals(region.computeRegionContentHash(), region.header().regionContentHash())) {
                    return new Verification(0, 0, name + ": region_content_hash does not match the records");
                }
                final List<int[]> present = new ArrayList<>();
                final int baseX = outcome.regionX() << SynxFormat.REGION_SHIFT, baseZ = outcome.regionZ() << SynxFormat.REGION_SHIFT;
                for (int x = Math.max(baseX, bounds.minX()); x <= Math.min(baseX + 31, bounds.maxX()); x++) {
                    for (int z = Math.max(baseZ, bounds.minZ()); z <= Math.min(baseZ + 31, bounds.maxZ()); z++) {
                        if (!(region.slot(x, z) instanceof SynxSlot.Present)) {
                            return new Verification(0, 0, name + ": no readable record for chunk " + x + ", " + z);
                        }
                        present.add(new int[]{x, z});
                    }
                }
                final Random random = new Random(ByteBuffer.wrap(region.header().regionContentHash()).getLong());
                for (int i = 0; i < request.samplesPerRegion() && !present.isEmpty(); i++) {
                    final int[] chunk = present.remove(random.nextInt(present.size()));
                    final SynxChunkRecord written = ((SynxSlot.Present) region.slot(chunk[0], chunk[1])).record();
                    samples.add(() -> {
                        final AreaView view = read(chunk[0], chunk[1], chunk[0], chunk[1]);
                        final LightResult light = LightEngine.computeArea(view, chunk[0], chunk[1], chunk[0], chunk[1]);
                        final SourceChunk sample = view.chunk(chunk[0], chunk[1]);
                        assert sample != null;
                        final SynxChunkRecord again = ChunkEncoder.encode(sample, light, source.minY(), settings,
                                request.registries(), request.handlers());
                        return again.equals(written) ? 0 : 1;
                    });
                }
            }
        } catch (IOException | SynxException | RuntimeException e) {
            return new Verification(0, 0, "digest check: " + e);
        }
        int diffs = 0;
        try {
            for (Future<Integer> sample : executor.invokeAll(samples)) diffs += sample.get();
        } catch (ExecutionException e) {
            return new Verification(samples.size(), diffs, "a sample failed to bake again: " + e.getCause());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new Verification(samples.size(), diffs, "interrupted");
        }
        return new Verification(samples.size(), diffs, null);
    }

    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
                ? os.getProcessCpuTime() : 0;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
