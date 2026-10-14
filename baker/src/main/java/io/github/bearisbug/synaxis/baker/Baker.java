package io.github.bearisbug.synaxis.baker;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.baker.anvil.AnvilSourceException;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.instance.block.Block;
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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.Deflater;

/**
 * {@code bake} of API-M3-003 and the {@code ENT-MapBuild} life cycle of §11.2 up to {@code published} or
 * {@code failed}: gate the source, bake every region in parallel with a one chunk light ring, derive the
 * {@code content_hash} and {@code build_id}, write the regions, assemble the far view packs (API-M3-014), write
 * the manifest, verify, and move the build directory into place.
 * <p>
 * Everything is written under {@code <maps_root>/<map_id>/.baking-*}, on the file system of the release, so
 * the last step is one atomic rename. Exit codes 3 and 4 leave that directory behind for inspection (§11.2),
 * unless it could not be created; every other refusal leaves nothing.
 */
public final class Baker {
    /** {@code baker_version}: raised by every change to what the baker writes for the same input. */
    public static final int VERSION = 2;
    /** The first {@code baker_version} that writes far view packs; the builds of earlier ones have none. */
    static final int FAR_VIEW_SINCE = 2;
    /**
     * {@code compression_threshold} of every region header: segment ① is framed for it, and a shard whose process
     * runs another refuses the build (synx-compression-mismatch).
     */
    public static final int COMPRESSION_THRESHOLD = 256;
    /** {@code compression_level} of every region header, the Deflater level of segments ① and ③. */
    public static final int COMPRESSION_LEVEL = Deflater.DEFAULT_COMPRESSION;

    /** 0: published; {@code verify} and {@code farview-export} exit with it too when they pass. */
    public static final int EXIT_PUBLISHED = 0;
    /** 2: the source cannot be read or fails the gate; nothing is written (§11.2). */
    public static final int EXIT_SOURCE = 2;
    /**
     * 3: baking → failed: a region task, or creating the work directory, writing the regions, the far view packs
     * or the draft manifest.
     */
    public static final int EXIT_REGION = 3;
    /**
     * 4: verifying → failed, or the verified build cannot be written or moved into place; also a {@code verify}
     * or {@code farview-export} that does not pass.
     */
    public static final int EXIT_VERIFY = 4;
    /** 5: the same build_id is already published; the work directory is deleted. */
    public static final int EXIT_EXISTS = 5;
    /** 6: {@code LightEngine.VERSION} has not passed the light parity test; refused before anything is read. */
    public static final int EXIT_LIGHT_VERSION = 6;

    /**
     * What to bake and where.
     *
     * @param handlers              the handler of a namespace, null when it has none; segment ① carries only its tags
     * @param verifiedLightVersions {@link LightVersions#verified()} outside of tests
     * @param samplesPerRegion      chunks per region the verification bakes again and compares byte for byte
     * @param sourceSpec            the {@code --source} of the command line, which {@code EVT:map_build_started}
     *                              records as {@code source_spec}; null for a bake that does not come from it
     */
    public record Request(String mapId, BakeSource source, Path mapsRoot, int threads, Path logDir,
                          Registries registries, Function<String, @Nullable BlockHandler> handlers,
                          Set<Integer> verifiedLightVersions, int samplesPerRegion, @Nullable String sourceSpec) {
        public Request {
            if (threads < 1) throw new IllegalArgumentException("threads >= 1: " + threads);
            if (samplesPerRegion < 1) throw new IllegalArgumentException("samples per region >= 1: " + samplesPerRegion);
            verifiedLightVersions = Set.copyOf(verifiedLightVersions);
        }

        /** A request with no {@code --source} to record, as the tests and fixtures make one. */
        public Request(String mapId, BakeSource source, Path mapsRoot, int threads, Path logDir, Registries registries,
                       Function<String, @Nullable BlockHandler> handlers, Set<Integer> verifiedLightVersions,
                       int samplesPerRegion) {
            this(mapId, source, mapsRoot, threads, logDir, registries, handlers, verifiedLightVersions, samplesPerRegion,
                    null);
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

    /** What {@link #verify} found: the problem is null when the build passes, and names the first differing chunk. */
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
        return bake(request, FarViewAssembler.Tamper.NONE);
    }

    /** {@link #bake(Request)} with the far view samples changed by a test before they are written. */
    static Result bake(Request request, FarViewAssembler.Tamper tamper) {
        if (!request.verifiedLightVersions().contains(LightEngine.VERSION)) {
            return new Result(EXIT_LIGHT_VERSION, null, null, "LightEngine.VERSION " + LightEngine.VERSION
                    + " has not passed the light parity test (verified: " + request.verifiedLightVersions() + ")");
        }
        try {
            request.source().checkGate(request.threads());
        } catch (IOException e) {
            return new Result(EXIT_SOURCE, null, null, e instanceof AnvilSourceException ? e.getMessage()
                    : "the source cannot be read: " + describe(e));
        }
        final ExecutorService executor = Executors.newFixedThreadPool(request.threads());
        try {
            return new Baker(request).run(executor, tamper);
        } finally {
            executor.shutdownNow();
        }
    }

    private Result run(ExecutorService executor, FarViewAssembler.Tamper tamper) {
        final JsonObject started = new JsonObject();
        started.addProperty("map_id", request.mapId());
        started.addProperty("source", source.kind());
        if (request.sourceSpec() != null) started.addProperty("source_spec", request.sourceSpec());
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
            if (!Files.isDirectory(mapDir)) publishedDirectory(Files.createDirectories(mapDir));
            work = Files.createTempDirectory(mapDir, ".baking-");
            Files.createDirectory(work.resolve("staging"));
            Files.createDirectory(work.resolve(FarViewAssembler.PARTS));
        } catch (IOException e) {
            return finish(new Result(EXIT_REGION, null, null, "cannot create the work directory: " + e), null, 0, 0);
        }

        // baking
        final List<int[]> regions = regions(source.bounds());
        final List<Future<RegionOutcome>> tasks = new ArrayList<>(regions.size());
        for (int[] region : regions) {
            tasks.add(executor.submit(() -> bakeRegion(region[0], region[1], work)));
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
            // Every task has run, so the message lists every failed region, one per line
            return finish(new Result(EXIT_REGION, work, null, failures.size() + " region(s) failed:\n"
                    + String.join("\n", failures)), null, failures.size(), 0);
        }

        // baking -> verifying: content hash, region files, far view packs, manifest draft
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
            publishedDirectory(Files.createDirectories(buildDir));
            publishedDirectory(Files.createDirectories(buildDir.resolve("regions")));
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
        final long assemblyStart = System.nanoTime();
        final List<SynxManifest.FarView> farview;
        try {
            farview = new FarViewAssembler(source.bounds(), source.minY(), source.height(), settings.protocolVersion(),
                    source.hiddenAreas(), request.registries().biome()).assemble(work, buildDir.resolve("farview"), tamper);
        } catch (IOException | RuntimeException e) {
            return finish(new Result(EXIT_REGION, work, null, "cannot assemble the far view packs: " + e), buildId, 0, 0);
        }
        final long assemblyMs = (System.nanoTime() - assemblyStart) / 1_000_000;
        final boolean anvil = source.dataVersion() != null;
        // SynxBuildVerifier reads manifest.json from the build, so a draft goes there before verifying; it is
        // written again with the verification's counts (§12.3)
        SynxManifest manifest = manifest(buildId, contentHash, manifestRegions, farview, startedAt, wallStart, cpuStart,
                new SynxManifest.Verify(0, 0, anvil ? sourceLightDiff : null));
        try {
            writeManifest(buildDir, manifest);
        } catch (IOException e) {
            return finish(new Result(EXIT_REGION, work, manifest, "cannot write the manifest: " + e), buildId, 0, 0);
        }

        // verifying
        final Verification verification = verify(buildDir, manifest, outcomes, executor);
        manifest = manifest(buildId, contentHash, manifestRegions, farview, startedAt, wallStart, cpuStart,
                new SynxManifest.Verify(verification.samples(), verification.diffs(), anvil ? sourceLightDiff : null));
        try {
            writeManifest(buildDir, manifest);
        } catch (IOException e) {
            return finish(new Result(EXIT_VERIFY, work, manifest, "cannot write the manifest: " + e), buildId, 0, 0);
        }
        if (verification.problem() != null) {
            return finish(new Result(EXIT_VERIFY, work, manifest, verification.problem()), buildId, 0, verification.diffs());
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
        final long farviewBytes = farview.stream().mapToLong(SynxManifest.FarView::bytes).sum();
        return finish(new Result(EXIT_PUBLISHED, target, manifest, "published " + target + ", " + bytes + " bytes; "
                + farview.size() + " far view pack(s), " + farviewBytes + " bytes, assembled in " + assemblyMs + " ms"),
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
            finished.addProperty("farview_bytes", manifest.farview().stream().mapToLong(SynxManifest.FarView::bytes).sum());
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
                                  List<SynxManifest.FarView> farview, Instant startedAt, long wallStart, long cpuStart,
                                  SynxManifest.Verify verify) {
        return new SynxManifest(request.mapId(), buildId, contentHash, MinecraftServer.VERSION_NAME,
                settings.protocolVersion(), settings.registryDigest(), LightEngine.VERSION, VERSION,
                new SynxManifest.World(source.minY(), source.height()), source.bounds(), regions, farview,
                new SynxManifest.Source(source.kind(), source.dataVersion(), source.lightCoverage()),
                new SynxManifest.Bake(startedAt.toString(), (System.nanoTime() - wallStart) / 1_000_000,
                        (processCpuNanos() - cpuStart) / 1_000_000, request.threads(), Runtime.version().toString()),
                verify);
    }

    /** Writes {@code manifest.json} forced to disk, as the build directory may be renamed into place next. */
    private static void writeManifest(Path buildDir, SynxManifest manifest) throws IOException {
        forced(publishedFile(Files.writeString(buildDir.resolve(SynxManifest.FILE_NAME), manifest.toJson())));
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
     * computation and encoded into a staging file, which the writer lays out in index order; their far view samples
     * go into the region's part file. The loop takes the chunks by x and then z, the order
     * {@link FarViewAssembler#writePart} takes.
     */
    private RegionOutcome bakeRegion(int regionX, int regionZ, Path work) throws IOException {
        final SynxManifest.Bounds bounds = source.bounds();
        final int minX = Math.max(regionX << SynxFormat.REGION_SHIFT, bounds.minX());
        final int minZ = Math.max(regionZ << SynxFormat.REGION_SHIFT, bounds.minZ());
        final int maxX = Math.min((regionX << SynxFormat.REGION_SHIFT) + SynxFormat.CHUNKS_PER_SIDE - 1, bounds.maxX());
        final int maxZ = Math.min((regionZ << SynxFormat.REGION_SHIFT) + SynxFormat.CHUNKS_PER_SIDE - 1, bounds.maxZ());
        final AreaView view = read(minX, minZ, maxX, maxZ);
        final LightResult light = LightEngine.computeArea(view, minX, minZ, maxX, maxZ);

        final SynxRegionWriter writer = new SynxRegionWriter(regionX, regionZ, settings);
        final List<List<FarViewPack.Sample>> farView = new ArrayList<>((maxX - minX + 1) * (maxZ - minZ + 1));
        long sourceLightDiff = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final SourceChunk chunk = view.chunk(x, z);
                assert chunk != null;
                final ChunkEncoder.Encoded encoded = ChunkEncoder.encode(chunk, light, source.minY(), settings,
                        request.registries(), request.handlers());
                writer.add(encoded.record());
                farView.add(encoded.farView());
                sourceLightDiff += ChunkEncoder.sourceLightDiff(chunk, light, source.minY());
            }
        }
        FarViewAssembler.writePart(work, regionX, regionZ, minX, minZ, maxX, maxZ, farView);
        final Path file = work.resolve("staging").resolve(SynxFormat.regionFileName(regionX, regionZ) + ".part");
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
     * {@code verify} of API-M3-003. Digests: {@link #checkDigests}, then every {@code region_content_hash}
     * recomputed from the written records and a record for every chunk inside the bounds. Samples: chunks picked
     * from each region's content hash, baked again on their own with just their 3×3 neighbourhood and compared
     * with the written record in all four segments ({@link SynxChunkRecord#equals}: ①, ② and ④ byte for byte, ③ as
     * decoded tables), and with their fine and coarse samples in the packs ({@link SampledFarView}).
     */
    private Verification verify(Path buildDir, SynxManifest manifest, List<RegionOutcome> outcomes,
                                ExecutorService executor) {
        try {
            final String farView = checkDigests(buildDir, manifest, request.registries());
            if (farView != null) return new Verification(0, 0, "digest check: " + farView);
        } catch (SynxException e) {
            return new Verification(0, 0, "digest check: " + e.getMessage());
        } catch (IOException e) {
            return new Verification(0, 0, "digest check: " + describe(e));
        }
        final List<SynxChunkRecord> written = new ArrayList<>();
        final SynxManifest.Bounds bounds = source.bounds();
        try (Arena arena = Arena.ofShared()) {
            for (RegionOutcome outcome : outcomes) {
                final String name = SynxFormat.regionFileName(outcome.regionX(), outcome.regionZ());
                final SynxRegion region = SynxRegion.open(buildDir.resolve("regions").resolve(name), arena);
                if (!Arrays.equals(region.computeRegionContentHash(), region.header().regionContentHash())) {
                    return new Verification(0, 0, name + ": region_content_hash does not match the records");
                }
                final List<SynxSlot.Present> present = new ArrayList<>();
                final int baseX = outcome.regionX() << SynxFormat.REGION_SHIFT, baseZ = outcome.regionZ() << SynxFormat.REGION_SHIFT;
                final int lastX = baseX + SynxFormat.CHUNKS_PER_SIDE - 1, lastZ = baseZ + SynxFormat.CHUNKS_PER_SIDE - 1;
                for (int x = Math.max(baseX, bounds.minX()); x <= Math.min(lastX, bounds.maxX()); x++) {
                    for (int z = Math.max(baseZ, bounds.minZ()); z <= Math.min(lastZ, bounds.maxZ()); z++) {
                        if (!(region.slot(x, z) instanceof SynxSlot.Present slot)) {
                            return new Verification(0, 0, name + ": no readable record for chunk " + x + ", " + z);
                        }
                        present.add(slot);
                    }
                }
                final Random random = new Random(ByteBuffer.wrap(region.header().regionContentHash()).getLong());
                for (int i = 0; i < request.samplesPerRegion() && !present.isEmpty(); i++) {
                    written.add(present.remove(random.nextInt(present.size())).record());
                }
            }
        } catch (SynxException e) {
            return new Verification(0, 0, "digest check: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            return new Verification(0, 0, "digest check: " + e);
        }
        // Tile by tile, so that the comparison reads each pack once and holds one at a time
        written.sort(Comparator.comparingInt((SynxChunkRecord record) -> FarViewPack.tile(record.chunkX()))
                .thenComparingInt(record -> FarViewPack.tile(record.chunkZ())));
        final List<Callable<ChunkEncoder.Encoded>> samples = new ArrayList<>(written.size());
        for (SynxChunkRecord record : written) {
            final int x = record.chunkX(), z = record.chunkZ();
            samples.add(() -> {
                final AreaView view = read(x, z, x, z);
                final LightResult light = LightEngine.computeArea(view, x, z, x, z);
                final SourceChunk sample = view.chunk(x, z);
                assert sample != null;
                return ChunkEncoder.encode(sample, light, source.minY(), settings, request.registries(), request.handlers());
            });
        }
        int diffs = 0;
        String first = "";
        try {
            final List<Future<ChunkEncoder.Encoded>> again = executor.invokeAll(samples);
            final SampledFarView packs = new SampledFarView(buildDir, new FarViewAssembler(manifest.bounds(), source.minY(),
                    source.height(), settings.protocolVersion(), source.hiddenAreas(), request.registries().biome()));
            for (int i = 0; i < again.size(); i++) {
                final ChunkEncoder.Encoded encoded = again.get(i).get();
                final SynxChunkRecord record = encoded.record();
                // The record first: when it differs, its far view samples are not compared
                final boolean recordDiffers = !record.equals(written.get(i));
                if (recordDiffers || !packs.matches(record.chunkX(), record.chunkZ(), encoded.farView())) {
                    if (diffs == 0) {
                        first = record.chunkX() + ", " + record.chunkZ() + (recordDiffers ? " (record)" : " (far view samples)");
                    }
                    diffs++;
                }
            }
        } catch (ExecutionException e) {
            return new Verification(samples.size(), diffs, "a sample failed to bake again: " + e.getCause());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new Verification(samples.size(), diffs, "interrupted");
        } catch (IOException e) {
            return new Verification(samples.size(), diffs, "the far view packs cannot be read: " + e);
        }
        return new Verification(samples.size(), diffs, diffs == 0 ? null
                : diffs + " of " + samples.size() + " sampled chunks differ; first chunk " + first);
    }

    /**
     * The digests of {@code verify} that {@code verify --build} checks too: what a shard checks at start-up
     * ({@link SynxBuildVerifier}), then the far view packs ({@link #checkFarView}).
     *
     * @return what does not match in the far view packs, null when everything does
     * @throws SynxException what does not match in the regions or the manifest
     */
    static @Nullable String checkDigests(Path buildDir, SynxManifest manifest, Registries registries)
            throws IOException, SynxException {
        SynxBuildVerifier.verify(buildDir, SynxExpectations.of(registries, COMPRESSION_THRESHOLD,
                manifest.world().minY(), manifest.world().height(), LightEngine.VERSION), manifest.bounds());
        return checkFarView(buildDir, manifest);
    }

    /**
     * The digest part of {@code verify} for the far view packs (API-M3-003): {@code farview[]} lists exactly the
     * tiles meeting the bounds; each pack has the size and SHA-256 of its entry; its coarse prefix is
     * {@code coarse_bytes} long, which is its {@code fine_offset}, and has the entry's {@code coarse_sha256}; its
     * header agrees with the entry, the manifest and this process; and every block of both layers reads. A build
     * of a baker_version before {@link #FAR_VIEW_SINCE} has no packs and nothing to check: a shard mounts it with
     * the far view off (API-M3-002).
     *
     * @return what does not match, null when everything does
     */
    static @Nullable String checkFarView(Path buildDir, SynxManifest manifest) throws IOException {
        if (manifest.bakerVersion() < FAR_VIEW_SINCE) return null;
        final List<String> tiles = FarViewAssembler.tiles(manifest.bounds()).stream().map(t -> t[0] + "," + t[1]).toList();
        final List<String> listed = manifest.farview().stream().map(p -> p.tileX() + "," + p.tileZ()).toList();
        if (!listed.equals(tiles)) return "farview[] lists the tiles " + listed + ", the bounds meet " + tiles;
        for (SynxManifest.FarView entry : manifest.farview()) {
            final String name = "farview/" + SynxFormat.farViewFileName(entry.tileX(), entry.tileZ());
            final Path file = buildDir.resolve(name);
            if (!Files.isRegularFile(file)) return name + " is missing";
            final byte[] pack = Files.readAllBytes(file);
            if (pack.length != entry.bytes()) return name + " is " + pack.length + " bytes, the manifest says " + entry.bytes();
            if (!Arrays.equals(SynxDigests.sha256(pack), entry.sha256())) {
                return name + ": the SHA-256 of the file does not match the manifest";
            }
            final FarViewPack.Reader reader;
            try {
                reader = FarViewPack.Reader.read(pack);
            } catch (FarViewPack.Invalid e) {
                return name + ": " + e.getMessage();
            }
            final FarViewPack.Header header = reader.header();
            if (header.fineOffset() != entry.coarseBytes()) {
                return name + ": coarse_bytes " + entry.coarseBytes() + " is not the pack's fine_offset " + header.fineOffset();
            }
            if (!Arrays.equals(SynxDigests.sha256(Arrays.copyOf(pack, (int) entry.coarseBytes())), entry.coarseSha256())) {
                return name + ": the coarse prefix does not match the manifest's coarse_sha256";
            }
            final List<String> fields = new ArrayList<>();
            if (header.baseLevel() != entry.baseLevel()) fields.add("base_level " + header.baseLevel() + " for " + entry.baseLevel());
            if (header.coarseLevel() != entry.coarseLevel()) fields.add("coarse_level " + header.coarseLevel() + " for " + entry.coarseLevel());
            if (header.tileX() != entry.tileX() || header.tileZ() != entry.tileZ()) {
                fields.add("tile " + header.tileX() + "," + header.tileZ() + " for " + entry.tileX() + "," + entry.tileZ());
            }
            if (header.minY() != manifest.world().minY()) fields.add("min_y " + header.minY() + " for " + manifest.world().minY());
            if (header.height() != manifest.world().height()) fields.add("height " + header.height() + " for " + manifest.world().height());
            if (header.blockStates() != Block.statesCount()) fields.add("block_states " + header.blockStates() + " for " + Block.statesCount());
            if (header.protocolVersion() != manifest.protocolVersion()) {
                fields.add("protocol_version " + header.protocolVersion() + " for " + manifest.protocolVersion());
            }
            if (!fields.isEmpty()) return name + ": the header has " + String.join(", ", fields);
            if (reader.coarseOnly()) return name + " is a coarse prefix without its fine layer";
            try {
                reader.verify();
            } catch (FarViewPack.Invalid e) {
                return name + ": " + e.getMessage();
            }
        }
        return null;
    }

    /**
     * The sampled part of {@code verify} for the far view packs: the fine samples a sampled chunk bakes into
     * again, and the coarse sample {@link FarViewPack#coarsen} takes from them, against those in the packs. A
     * sample a hidden area covers is checked against its replacement instead, whose height is taken again from
     * its ring in the packs.
     * <p>
     * It holds the pack of one tile and the blocks it inflated from it, and lets both go when a sample of another
     * tile is asked for, so what it holds does not grow with the map: one pack and at most the blocks of one tile,
     * 272 at base_level 2. {@link #verify} asks for the sampled chunks tile by tile, so the comparison reads each
     * pack once; the ring heights of the hidden areas, taken in the constructor, read the packs their rings meet
     * before that.
     */
    static final class SampledFarView {
        private final Path farview;
        private final FarViewAssembler assembler;
        /** The tile whose pack {@link #reader} holds. */
        private int heldTileX;
        private int heldTileZ;
        private FarViewPack.@Nullable Reader reader;
        /** The blocks inflated from the pack of the tile. */
        private final Map<FarViewPack.Block, List<FarViewPack.Sample>> blocks = new HashMap<>();
        private final List<FarViewAssembler.Replacement> fineHidden;
        private final List<FarViewAssembler.Replacement> coarseHidden;

        /** A sample of a pack and the name of its biome. */
        private record Named(FarViewPack.Sample sample, String biome) {
        }

        /**
         * The check of the packs of a build.
         *
         * @param assembler the assembler of the bake, for its bounds and hidden areas, the hidden sample and the
         *                  biome names
         */
        SampledFarView(Path buildDir, FarViewAssembler assembler) throws IOException {
            this.farview = buildDir.resolve("farview");
            this.assembler = assembler;
            this.fineHidden = assembler.replacements(FarViewAssembler.BASE_LEVEL,
                    (x, z) -> Math.max(0, sample(FarViewPack.Layer.FINE, x, z).sample().top()));
            this.coarseHidden = assembler.replacements(FarViewPack.COARSE_LEVEL,
                    (x, z) -> Math.max(0, sample(FarViewPack.Layer.COARSE, x, z).sample().top()));
        }

        /**
         * Whether a chunk's fine samples baked again, and the coarse sample taken from them, are those of the packs.
         *
         * @param fine the chunk's fine samples by z and then x, each biome a registry id
         */
        boolean matches(int chunkX, int chunkZ, List<FarViewPack.Sample> fine) throws IOException {
            for (int i = 0; i < fine.size(); i++) {
                final int x = chunkX * FarViewAssembler.CHUNK_SAMPLES + i % FarViewAssembler.CHUNK_SAMPLES;
                final int z = chunkZ * FarViewAssembler.CHUNK_SAMPLES + i / FarViewAssembler.CHUNK_SAMPLES;
                if (!same(sample(FarViewPack.Layer.FINE, x, z), expected(fine.get(i), fineHidden, x, z))) return false;
            }
            return same(sample(FarViewPack.Layer.COARSE, chunkX, chunkZ),
                    expected(FarViewPack.coarsen(FarViewAssembler.BASE_LEVEL, fine), coarseHidden, chunkX, chunkZ));
        }

        /** What a sample baked again should be in the pack, its biome a registry id. */
        private FarViewPack.Sample expected(FarViewPack.Sample baked, List<FarViewAssembler.Replacement> hidden, int x, int z) {
            final int height = FarViewAssembler.replacedHeight(hidden, x, z);
            return height < 0 ? baked : FarViewAssembler.sample(assembler.hiddenSample(height));
        }

        private boolean same(Named actual, FarViewPack.Sample expected) {
            final FarViewPack.Sample sample = actual.sample();
            return actual.biome().equals(assembler.biomeName(expected.biome()))
                    && sample.equals(new FarViewPack.Sample(expected.groundState(), expected.fluidState(), expected.groundY(),
                    expected.fluidBottom(), expected.fluidTop(), sample.biome()));
        }

        /**
         * A sample of a layer by its grid coordinates; an empty column with no biome outside the bounds or where
         * the pack has no block.
         */
        private Named sample(FarViewPack.Layer layer, int x, int z) throws IOException {
            final int level = layer == FarViewPack.Layer.FINE ? FarViewAssembler.BASE_LEVEL : FarViewPack.COARSE_LEVEL;
            // A chunk is 16 = 1 << 4 blocks across, a sample of the layer 1 << level
            final int chunkX = x >> (4 - level), chunkZ = z >> (4 - level);
            if (!assembler.bounds().contains(chunkX, chunkZ)) return new Named(FarViewPack.Sample.EMPTY, "");
            final int tileX = FarViewPack.tile(chunkX), tileZ = FarViewPack.tile(chunkZ);
            final int side = FarViewPack.TILE_BLOCKS >> level;
            final int localX = x - tileX * side, localZ = z - tileZ * side;
            try {
                if (reader == null || heldTileX != tileX || heldTileZ != tileZ) {
                    // The held pack and its blocks go before the next pack is read, so that only one is ever held
                    reader = null;
                    blocks.clear();
                    reader = FarViewPack.Reader.read(Files.readAllBytes(farview.resolve(SynxFormat.farViewFileName(tileX, tileZ))));
                    heldTileX = tileX;
                    heldTileZ = tileZ;
                }
                final FarViewPack.Reader pack = reader;
                for (FarViewPack.Block block : pack.blocks(layer)) {
                    if (block.bx() != localX / FarViewPack.BLOCK_SIDE || block.bz() != localZ / FarViewPack.BLOCK_SIDE) continue;
                    List<FarViewPack.Sample> samples = blocks.get(block);
                    if (samples == null) {
                        samples = pack.samples(block);
                        blocks.put(block, samples);
                    }
                    final FarViewPack.Sample sample = samples.get((localZ % FarViewPack.BLOCK_SIDE) * FarViewPack.BLOCK_SIDE
                            + localX % FarViewPack.BLOCK_SIDE);
                    return new Named(sample, pack.biomes().get(sample.biome()));
                }
                return new Named(FarViewPack.Sample.EMPTY, "");
            } catch (FarViewPack.Invalid e) {
                throw new IOException("tile " + tileX + "," + tileZ + ": " + e.getMessage(), e);
            }
        }

        /** For the tests: the tiles whose pack this holds. */
        int tilesHeld() {
            return reader == null ? 0 : 1;
        }

        /** For the tests: the blocks this holds inflated. */
        int blocksHeld() {
            return blocks.size();
        }
    }

    /**
     * An I/O failure for a message: a JDK exception with its class, as {@code NoSuchFileException} and
     * {@code AccessDeniedException} say no more than a path; one the baker words itself as it is.
     */
    static String describe(IOException e) {
        return e.getClass() == IOException.class || e instanceof AnvilSourceException ? e.getMessage() : e.toString();
    }

    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
                ? os.getProcessCpuTime() : 0;
    }

    /**
     * A directory of the release, 0755 where the file system has POSIX permissions, whatever the umask: the
     * shard refuses a release group or others may write ({@code docs/DESIGN-M3.md} {@code API-M3-002} 文件信任).
     */
    static Path publishedDirectory(Path directory) throws IOException {
        return publishedMode(directory, "rwxr-xr-x");
    }

    /** A file of the release, 0644 where the file system has POSIX permissions, see {@link #publishedDirectory}. */
    static Path publishedFile(Path file) throws IOException {
        return publishedMode(file, "rw-r--r--");
    }

    /**
     * A file forced to disk before it gets its name in a release or an export by a rename or a link, so that a
     * power loss cannot leave the name on an empty or cut file.
     */
    static Path forced(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        return file;
    }

    private static Path publishedMode(Path path, String permissions) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
        return path;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
