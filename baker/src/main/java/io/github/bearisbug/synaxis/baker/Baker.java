package io.github.bearisbug.synaxis.baker;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.baker.anvil.AnvilSourceException;
import io.github.bearisbug.synaxis.baker.anvil.HiddenArea;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * {@code bake} of API-M3-003 and the {@code ENT-MapBuild} life cycle of §11.2 up to {@code published} or
 * {@code failed}: gate the source, bake every region in parallel with a one chunk light ring, derive the
 * {@code content_hash} and {@code build_id}, write the regions, assemble the far view packs (API-M3-014), write
 * the manifest, verify, and move the build directory into place.
 * <p>
 * Everything is written under {@code <maps_root>/<map_id>/.baking-*}, on the file system of the release, so
 * the last step is one atomic rename. A bake that fails in a region or in the verification leaves that
 * directory behind for inspection (§11.2); every other refusal leaves nothing.
 */
public final class Baker {
    /** {@code baker_version}: raised by every change to what the baker writes for the same input. */
    public static final int VERSION = 2;
    /** The first {@code baker_version} that writes far view packs; the builds of earlier ones have none. */
    static final int FAR_VIEW_SINCE = 2;
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
                    : "the source cannot be read: " + e.getMessage());
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

    private static void writeManifest(Path buildDir, SynxManifest manifest) throws IOException {
        publishedFile(Files.writeString(buildDir.resolve(SynxManifest.FILE_NAME), manifest.toJson()));
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
     * computation, encoded in index order into a staging file; their far view samples go into the region's part
     * file.
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
     * {@code verify} of API-M3-003. Digests: the start-up check a shard runs ({@link SynxBuildVerifier}), every
     * {@code region_content_hash} recomputed from the written records, a record for every chunk inside the
     * bounds, and the far view packs ({@link #checkFarView}). Samples: chunks picked from each region's content
     * hash, baked again on their own with just their 3×3 neighbourhood and compared with the written record,
     * segment ① byte for byte, and with their fine and coarse samples in the packs ({@link SampledFarView}).
     */
    private Verification verify(Path buildDir, SynxManifest manifest, List<RegionOutcome> outcomes,
                                ExecutorService executor) {
        try {
            SynxBuildVerifier.verify(buildDir, SynxExpectations.of(request.registries(), COMPRESSION_THRESHOLD,
                    source.minY(), source.height(), LightEngine.VERSION), manifest.bounds());
            final String farView = checkFarView(buildDir, manifest);
            if (farView != null) return new Verification(0, 0, "digest check: " + farView);
        } catch (IOException | SynxException e) {
            return new Verification(0, 0, "digest check: " + e.getMessage());
        }
        final List<SynxChunkRecord> written = new ArrayList<>();
        final List<Callable<ChunkEncoder.Encoded>> samples = new ArrayList<>();
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
                    written.add(((SynxSlot.Present) region.slot(chunk[0], chunk[1])).record());
                    samples.add(() -> {
                        final AreaView view = read(chunk[0], chunk[1], chunk[0], chunk[1]);
                        final LightResult light = LightEngine.computeArea(view, chunk[0], chunk[1], chunk[0], chunk[1]);
                        final SourceChunk sample = view.chunk(chunk[0], chunk[1]);
                        assert sample != null;
                        return ChunkEncoder.encode(sample, light, source.minY(), settings, request.registries(),
                                request.handlers());
                    });
                }
            }
        } catch (IOException | SynxException | RuntimeException e) {
            return new Verification(0, 0, "digest check: " + e);
        }
        int diffs = 0;
        try {
            final List<Future<ChunkEncoder.Encoded>> again = executor.invokeAll(samples);
            final SampledFarView packs = new SampledFarView(buildDir, manifest, new FarViewAssembler(manifest.bounds(),
                    source.minY(), source.height(), settings.protocolVersion(), source.hiddenAreas(),
                    request.registries().biome()), source.hiddenAreas());
            // Tile by tile, so that the far view check reads each pack once and holds one at a time
            final List<Integer> byTile = IntStream.range(0, again.size()).boxed()
                    .sorted(Comparator.comparingInt((Integer i) -> FarViewPack.tile(written.get(i).chunkX()))
                            .thenComparingInt(i -> FarViewPack.tile(written.get(i).chunkZ())))
                    .toList();
            for (int i : byTile) {
                final ChunkEncoder.Encoded encoded = again.get(i).get();
                final SynxChunkRecord record = encoded.record();
                if (!record.equals(written.get(i)) || !packs.matches(record.chunkX(), record.chunkZ(), encoded.farView())) {
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
        return new Verification(samples.size(), diffs, null);
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
            final String name = "farview/" + FarViewAssembler.fileName(entry.tileX(), entry.tileZ());
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
     * 272 at base_level 2. {@link #verify} asks for the sampled chunks tile by tile, so each pack is read once.
     */
    static final class SampledFarView {
        private final Path farview;
        private final SynxManifest manifest;
        private final FarViewAssembler assembler;
        private long tile;
        private FarViewPack.@Nullable Reader reader;
        /** The blocks inflated from the pack of the tile, by layer and block coordinates. */
        private final Map<String, List<FarViewPack.Sample>> blocks = new HashMap<>();
        private final List<FarViewAssembler.Replacement> fineHidden;
        private final List<FarViewAssembler.Replacement> coarseHidden;

        /** A sample of a pack and the name of its biome. */
        private record Named(FarViewPack.Sample sample, String biome) {
        }

        /**
         * @param assembler the assembler of the bake, for the hidden sample and the biome names
         * @param hidden    the hidden areas of the source, corners as min and max
         */
        SampledFarView(Path buildDir, SynxManifest manifest, FarViewAssembler assembler, List<HiddenArea> hidden)
                throws IOException {
            this.farview = buildDir.resolve("farview");
            this.manifest = manifest;
            this.assembler = assembler;
            this.fineHidden = FarViewAssembler.hide(hidden, FarViewAssembler.BASE_LEVEL,
                    (x, z) -> Math.max(0, sample(FarViewPack.Layer.FINE, x, z).sample().top()));
            this.coarseHidden = FarViewAssembler.hide(hidden, FarViewPack.COARSE_LEVEL,
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
            final int chunkX = x >> (4 - level), chunkZ = z >> (4 - level);
            final SynxManifest.Bounds bounds = manifest.bounds();
            if (chunkX < bounds.minX() || chunkX > bounds.maxX() || chunkZ < bounds.minZ() || chunkZ > bounds.maxZ()) {
                return new Named(FarViewPack.Sample.EMPTY, "");
            }
            final int tileX = FarViewPack.tile(chunkX), tileZ = FarViewPack.tile(chunkZ);
            final int side = FarViewPack.TILE_BLOCKS >> level;
            final int localX = x - tileX * side, localZ = z - tileZ * side;
            try {
                if (reader == null || tile != key(tileX, tileZ)) {
                    reader = null;
                    blocks.clear();
                    reader = FarViewPack.Reader.read(Files.readAllBytes(farview.resolve(FarViewAssembler.fileName(tileX, tileZ))));
                    tile = key(tileX, tileZ);
                }
                final FarViewPack.Reader pack = reader;
                for (FarViewPack.Block block : pack.blocks(layer)) {
                    if (block.bx() != localX / FarViewPack.BLOCK_SIDE || block.bz() != localZ / FarViewPack.BLOCK_SIDE) continue;
                    final String cached = layer + " " + block.bx() + " " + block.bz();
                    List<FarViewPack.Sample> samples = blocks.get(cached);
                    if (samples == null) {
                        samples = pack.samples(block);
                        blocks.put(cached, samples);
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

        private static long key(int x, int z) {
            return (long) x << 32 | (z & 0xFFFF_FFFFL);
        }
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
