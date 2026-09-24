package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.MinecraftServer;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The start-up check of API-M3-002: a shard refuses a map release unless the manifest matches the
 * running process and every region file its view reaches matches the manifest, byte for byte.
 * Wiring it into shard start-up (non-zero exit, no bind) is API-M3-005's job.
 */
public final class SynxBuildVerifier {
    private SynxBuildVerifier() {
    }

    /**
     * Checks {@code <maps_root>/<map_id>/<build_id>/}. Only the regions overlapping both {@code scope}
     * (the shard's {@code view_bounds}) and the manifest bounds are opened; each of those is hashed
     * in full.
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

        final Map<Long, SynxManifest.Region> regions = new HashMap<>();
        for (SynxManifest.Region region : manifest.regions()) regions.put(key(region.regionX(), region.regionZ()), region);
        final SynxManifest.Bounds bounds = manifest.bounds();
        final int minX = Math.max(scope.minX(), bounds.minX()), maxX = Math.min(scope.maxX(), bounds.maxX());
        final int minZ = Math.max(scope.minZ(), bounds.minZ()), maxZ = Math.min(scope.maxZ(), bounds.maxZ());
        if (minX > maxX || minZ > maxZ) return manifest;
        for (int regionZ = SynxFormat.region(minZ); regionZ <= SynxFormat.region(maxZ); regionZ++) {
            for (int regionX = SynxFormat.region(minX); regionX <= SynxFormat.region(maxX); regionX++) {
                final String name = SynxFormat.regionFileName(regionX, regionZ);
                final SynxManifest.Region entry = regions.get(key(regionX, regionZ));
                if (entry == null) {
                    throw new SynxException(SynxError.MANIFEST, name, "inside the bounds but not listed in the manifest");
                }
                checkRegion(buildDir.resolve("regions").resolve(name), name, entry, manifest, expectations);
            }
        }
        return manifest;
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

    private static void checkRegion(Path path, String name, SynxManifest.Region entry, SynxManifest manifest,
                                    SynxExpectations expectations) throws IOException, SynxException {
        if (!Files.isRegularFile(path)) throw new SynxException(SynxError.MANIFEST, name, "listed but missing");
        final long size = Files.size(path);
        if (size != entry.bytes()) {
            throw new SynxException(SynxError.PAYLOAD_HASH, name, size + " bytes, the manifest says " + entry.bytes());
        }
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(path, arena);
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
        }
    }

    private static long key(int regionX, int regionZ) {
        return (long) regionX << 32 | (regionZ & 0xFFFFFFFFL);
    }
}
