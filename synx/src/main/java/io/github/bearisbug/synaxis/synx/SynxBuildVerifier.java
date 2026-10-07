package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.MinecraftServer;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The start-up check of API-M3-002: a shard refuses a map release unless the manifest matches the
 * running process and every region file its view reaches matches the manifest: its size, its header,
 * and {@code payload_sha256}, which is the SHA-256 of every byte from offset 16,640 on, the records.
 * The 16,384 byte offset index before them is not hashed; each entry is held to the file and the
 * record it names to its {@code crc32c} as a chunk loads ({@link SynxRegion#slot}).
 * <p>
 * {@link #open} is the shard's entry: it checks on the mappings it then hands on in a
 * {@link VerifiedBuild}, so what the shard loads and spawns on is what was checked. {@link #verify}
 * is the baker's: it reads the manifest from the directory and unmaps each region once checked.
 */
public final class SynxBuildVerifier {
    private SynxBuildVerifier() {
    }

    /**
     * The region files {@link #open} maps for {@code scope}: one for every region overlapping both
     * {@code scope} and the manifest bounds, whether the manifest lists it and the file exists or not.
     *
     * @param scope the shard's {@code view_bounds}
     * @return the paths under {@code <build_id>/regions/}, by z and then x
     */
    public static List<Path> regionFiles(Path buildDir, SynxManifest manifest, SynxManifest.Bounds scope) {
        return regions(manifest, scope).stream().map(region -> file(buildDir, region)).toList();
    }

    /** The regions overlapping both {@code scope} and the manifest bounds, by z and then x, as {@code {rx, rz}}. */
    private static List<int[]> regions(SynxManifest manifest, SynxManifest.Bounds scope) {
        final List<int[]> regions = new ArrayList<>();
        final SynxManifest.Bounds bounds = manifest.bounds();
        final int minX = Math.max(scope.minX(), bounds.minX()), maxX = Math.min(scope.maxX(), bounds.maxX());
        final int minZ = Math.max(scope.minZ(), bounds.minZ()), maxZ = Math.min(scope.maxZ(), bounds.maxZ());
        if (minX > maxX || minZ > maxZ) return List.of();
        for (int regionZ = SynxFormat.region(minZ); regionZ <= SynxFormat.region(maxZ); regionZ++) {
            for (int regionX = SynxFormat.region(minX); regionX <= SynxFormat.region(maxX); regionX++) {
                regions.add(new int[]{regionX, regionZ});
            }
        }
        return regions;
    }

    private static Path file(Path buildDir, int[] region) {
        return buildDir.resolve("regions").resolve(SynxFormat.regionFileName(region[0], region[1]));
    }

    /**
     * Checks {@code <maps_root>/<map_id>/<build_id>/} against a manifest the caller read once: the
     * manifest against the process, then each region of {@link #regionFiles}, mapped into
     * {@code Arena.ofShared()} once and checked on that mapping, its size the length of the mapping.
     * Nothing is read by its path again afterwards.
     *
     * @param manifest the manifest the caller read from {@code buildDir}
     * @param scope    the shard's {@code view_bounds}
     * @return the release with its regions mapped, which stay mapped for the life of the process
     * @throws IOException   if a region file cannot be mapped
     * @throws SynxException with the error code of {@code docs/DESIGN-M3.md} §14 and the offending file
     */
    public static VerifiedBuild open(Path buildDir, SynxManifest manifest, SynxExpectations expectations,
                                     SynxManifest.Bounds scope) throws IOException, SynxException {
        checkManifest(manifest, String.valueOf(buildDir.getFileName()), expectations);
        final Map<Long, SynxManifest.Region> listed = listed(manifest);
        final Map<Long, SynxRegion> mapped = new HashMap<>();
        // Shared and never closed: queued packets and handle chunks keep reading the mappings
        final Arena arena = Arena.ofShared();
        try {
            for (int[] region : regions(manifest, scope)) {
                mapped.put(VerifiedBuild.key(region[0], region[1]),
                        checkRegion(buildDir, region, listed, manifest, expectations, arena));
            }
        } catch (IOException | SynxException | RuntimeException e) {
            arena.close();
            throw e;
        }
        return new VerifiedBuild(manifest, mapped);
    }

    /**
     * Checks {@code <maps_root>/<map_id>/<build_id>/}, reading its manifest. Only the regions
     * overlapping both {@code scope} (the shard's {@code view_bounds}) and the manifest bounds are
     * opened; each of those is hashed in full and unmapped again.
     *
     * @return the manifest that passed
     * @throws SynxException with the error code of {@code docs/DESIGN-M3.md} §14 and the offending file
     */
    public static SynxManifest verify(Path buildDir, SynxExpectations expectations, SynxManifest.Bounds scope)
            throws IOException, SynxException {
        final Path manifestFile = buildDir.resolve(SynxManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            throw new SynxException(SynxError.MANIFEST, SynxManifest.FILE_NAME, "missing in " + buildDir);
        }
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(manifestFile));
        checkManifest(manifest, String.valueOf(buildDir.getFileName()), expectations);
        final Map<Long, SynxManifest.Region> listed = listed(manifest);
        for (int[] region : regions(manifest, scope)) {
            try (Arena arena = Arena.ofConfined()) {
                checkRegion(buildDir, region, listed, manifest, expectations, arena);
            }
        }
        return manifest;
    }

    private static Map<Long, SynxManifest.Region> listed(SynxManifest manifest) {
        final Map<Long, SynxManifest.Region> regions = new HashMap<>();
        for (SynxManifest.Region region : manifest.regions()) {
            regions.put(VerifiedBuild.key(region.regionX(), region.regionZ()), region);
        }
        return regions;
    }

    private static void checkManifest(SynxManifest manifest, String directory, SynxExpectations expectations)
            throws SynxException {
        final String file = SynxManifest.FILE_NAME;
        if (!manifest.buildId().equals(directory)) {
            throw new SynxException(SynxError.MANIFEST, file, "build_id " + manifest.buildId() + " in directory " + directory);
        }
        final String buildId = SynxManifest.buildId(manifest.mapId(), manifest.contentHash(), manifest.protocolVersion());
        if (!manifest.buildId().equals(buildId)) {
            throw new SynxException(SynxError.MANIFEST, file, "build_id " + manifest.buildId()
                    + " does not follow from map_id, content_hash and protocol_version: " + buildId);
        }
        if (manifest.protocolVersion() != expectations.protocolVersion()
                || !manifest.mcVersion().equals(MinecraftServer.VERSION_NAME)) {
            throw new SynxException(SynxError.PROTOCOL_MISMATCH, file, "mc_version " + manifest.mcVersion()
                    + " protocol_version " + manifest.protocolVersion() + ", process " + MinecraftServer.VERSION_NAME
                    + " " + expectations.protocolVersion());
        }
        if (!Arrays.equals(manifest.registryDigest(), expectations.registryDigest())) {
            throw new SynxException(SynxError.REGISTRY_MISMATCH, file, "registry_digest "
                    + SynxDigests.hex(manifest.registryDigest()) + ", process " + SynxDigests.hex(expectations.registryDigest()));
        }
        if (manifest.world().minY() != expectations.minY() || manifest.world().height() != expectations.height()) {
            throw new SynxException(SynxError.WORLD_MISMATCH, file, "min_y " + manifest.world().minY() + " height "
                    + manifest.world().height() + ", process " + expectations.minY() + " " + expectations.height());
        }
        if (manifest.lightEngineVersion() != expectations.lightEngineVersion()) {
            throw new SynxException(SynxError.LIGHT_ENGINE_MISMATCH, file, "light_engine_version "
                    + manifest.lightEngineVersion() + ", process " + expectations.lightEngineVersion() + ", rebake");
        }
    }

    /**
     * One region of the scope: listed in the manifest, its file present, mapped into {@code arena} and
     * as long as the manifest says, a header of this process and of this release, the
     * {@code payload_sha256} of the manifest over the mapped bytes, as many records as the manifest says.
     */
    private static SynxRegion checkRegion(Path buildDir, int[] at, Map<Long, SynxManifest.Region> listed,
                                          SynxManifest manifest, SynxExpectations expectations, Arena arena)
            throws IOException, SynxException {
        final Path path = file(buildDir, at);
        final String name = path.getFileName().toString();
        final SynxManifest.Region entry = listed.get(VerifiedBuild.key(at[0], at[1]));
        if (entry == null) {
            throw new SynxException(SynxError.MANIFEST, name, "inside the bounds but not listed in the manifest");
        }
        if (!Files.isRegularFile(path)) throw new SynxException(SynxError.MANIFEST, name, "listed but missing");
        final MemorySegment file = SynxRegion.map(path, arena);
        if (file.byteSize() != entry.bytes()) {
            throw new SynxException(SynxError.PAYLOAD_HASH, name, file.byteSize() + " bytes, the manifest says "
                    + entry.bytes());
        }
        final SynxRegion region = SynxRegion.of(name, file);
        final SynxHeader header = region.header();
        region.checkAgainst(expectations);
        if (header.regionX() != entry.regionX() || header.regionZ() != entry.regionZ()) {
            throw new SynxException(SynxError.MANIFEST, name, "header says region " + header.regionX() + ","
                    + header.regionZ());
        }
        if (!Arrays.equals(header.contentHash(), manifest.contentHash())) {
            throw new SynxException(SynxError.MANIFEST, name, "content_hash " + SynxDigests.hex(header.contentHash())
                    + " belongs to another build");
        }
        if (header.bakerVersion() != manifest.bakerVersion()) {
            throw new SynxException(SynxError.MANIFEST, name, "baker_version " + header.bakerVersion()
                    + ", the manifest says " + manifest.bakerVersion());
        }
        if (!Arrays.equals(header.payloadSha256(), entry.payloadSha256())) {
            throw new SynxException(SynxError.PAYLOAD_HASH, name, "payload_sha256 " + SynxDigests.hex(header.payloadSha256())
                    + ", the manifest says " + SynxDigests.hex(entry.payloadSha256()));
        }
        region.verifyPayload();
        if (region.chunkCount() != entry.chunks()) {
            throw new SynxException(SynxError.MANIFEST, name, region.chunkCount() + " chunk records, the manifest says "
                    + entry.chunks());
        }
        return region;
    }
}
