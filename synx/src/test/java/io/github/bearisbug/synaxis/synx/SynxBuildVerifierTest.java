package io.github.bearisbug.synaxis.synx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start-up check of API-M3-002 on a release directory assembled from the frozen vectors
 * (REQ-M3-002, §19 「清单不符时拒绝起服」「光照引擎版本变化强制重烘」).
 */
public class SynxBuildVerifierTest {

    @TempDir
    Path mapsRoot;

    private Path build;
    private SynxManifest manifest;

    @BeforeEach
    public void assembleRelease() throws Exception {
        final String json = Files.readString(SynxVectors.DIR.resolve(SynxManifest.FILE_NAME));
        manifest = SynxManifest.fromJson(json);
        build = mapsRoot.resolve(manifest.mapId()).resolve(manifest.buildId());
        Files.createDirectories(build.resolve("regions"));
        Files.writeString(build.resolve(SynxManifest.FILE_NAME), json);
        for (SynxVectors.Vector vector : SynxVectors.vectors()) {
            Files.copy(vector.file(), region(vector.regionX(), vector.regionZ()));
        }
    }

    private Path region(int regionX, int regionZ) {
        return build.resolve("regions").resolve(SynxFormat.regionFileName(regionX, regionZ));
    }

    private SynxException refused(SynxExpectations expectations) {
        return assertThrows(SynxException.class,
                () -> SynxBuildVerifier.verify(build, expectations, manifest.bounds()));
    }

    @Test
    public void consistentReleaseVerifies() throws Exception {
        final SynxManifest verified = SynxBuildVerifier.verify(build, SynxVectors.expectations(), manifest.bounds());
        assertEquals(manifest.buildId(), verified.buildId());
    }

    @Test
    public void oneChangedByteRefusesWithThePayloadHashAndTheFileName() throws IOException {
        final Path file = region(-1, 0);
        final byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 100] ^= 0x01;
        Files.write(file, bytes);

        final SynxException refused = refused(SynxVectors.expectations());
        assertEquals(SynxError.PAYLOAD_HASH, refused.error());
        assertTrue(refused.getMessage().startsWith("synx-payload-hash: "), refused.getMessage());
        assertTrue(refused.getMessage().contains("r.-1.0.synx"), refused.getMessage());
    }

    @Test
    public void lightEngineVersionOneBehindForcesARebake() {
        final SynxExpectations current = SynxVectors.expectations();
        final SynxExpectations newer = new SynxExpectations(current.protocolVersion(), current.registryDigest(),
                current.compressionThreshold(), current.minY(), current.height(), current.lightEngineVersion() + 1);
        final SynxException refused = refused(newer);
        assertEquals(SynxError.LIGHT_ENGINE_MISMATCH, refused.error());
        assertTrue(refused.getMessage().contains(SynxManifest.FILE_NAME), refused.getMessage());
    }

    @Test
    public void processOnAnotherProtocolOrRegistryIsRefused() {
        final SynxExpectations current = SynxVectors.expectations();
        assertEquals(SynxError.PROTOCOL_MISMATCH, refused(new SynxExpectations(777, current.registryDigest(),
                current.compressionThreshold(), current.minY(), current.height(), current.lightEngineVersion())).error());
        assertEquals(SynxError.REGISTRY_MISMATCH, refused(new SynxExpectations(776, new byte[32],
                current.compressionThreshold(), current.minY(), current.height(), current.lightEngineVersion())).error());
        assertEquals(SynxError.COMPRESSION_MISMATCH, refused(new SynxExpectations(776, current.registryDigest(),
                512, current.minY(), current.height(), current.lightEngineVersion())).error());
        assertEquals(SynxError.WORLD_MISMATCH, refused(new SynxExpectations(776, current.registryDigest(),
                current.compressionThreshold(), -64, 384, current.lightEngineVersion())).error());
    }

    @Test
    public void headerThatDisagreesWithTheManifestIsRefused() throws IOException {
        // A region from another build: its header protocol no longer matches the manifest's
        final Path file = region(0, 0);
        final byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 775);
        Files.write(file, bytes);
        final SynxException refused = refused(SynxVectors.expectations());
        assertEquals(SynxError.PROTOCOL_MISMATCH, refused.error());
        assertTrue(refused.getMessage().contains("r.0.0.synx"), refused.getMessage());

        bytes[64] ^= 0x01; // content_hash
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 776);
        Files.write(file, bytes);
        assertEquals(SynxError.MANIFEST, refused(SynxVectors.expectations()).error());
    }

    @Test
    public void missingRegionFileIsRefused() throws IOException {
        Files.delete(region(0, -1));
        final SynxException missing = refused(SynxVectors.expectations());
        assertEquals(SynxError.MANIFEST, missing.error());
        assertTrue(missing.getMessage().contains("r.0.-1.synx"), missing.getMessage());
    }

    @Test
    public void truncatedRegionIsRefused() throws IOException {
        final Path file = region(-1, 0);
        final byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertEquals(SynxError.PAYLOAD_HASH, refused(SynxVectors.expectations()).error());
    }

    @Test
    public void directoryThatIsNotTheBuildIdIsRefused() throws IOException {
        final Path renamed = build.resolveSibling("vectors-000000000000-p776");
        Files.move(build, renamed);
        build = renamed;
        assertEquals(SynxError.MANIFEST, refused(SynxVectors.expectations()).error());
    }

    @Test
    public void onlyRegionsInScopeAreChecked() throws Exception {
        final Path file = region(-1, 0);
        final byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);
        // The shard of chunks 0..31 x 0..31 plus a 16 chunk view border reaches regions (-1..0, -1..0)
        assertEquals(SynxError.PAYLOAD_HASH, assertThrows(SynxException.class, () -> SynxBuildVerifier.verify(build,
                SynxVectors.expectations(), new SynxManifest.Bounds(-16, -16, 47, 47))).error());
        // A shard whose view stays inside region (0,0) never opens it
        SynxBuildVerifier.verify(build, SynxVectors.expectations(), new SynxManifest.Bounds(0, 0, 31, 31));
    }

    /**
     * §19 「校验与载入用同一份映射」, the {@code :synx} half (M3-S): {@link SynxBuildVerifier#open} maps each
     * region of the scope once, holds the length of that mapping to the manifest and checks on it, and
     * hands the mapping on. Another file renamed over a checked one afterwards changes nothing it reads.
     */
    @Test
    public void openChecksOnTheMappingItHandsOnAndAFileRenamedOverItChangesNothing() throws Exception {
        assertEquals(List.of(region(-1, -1), region(0, -1), region(-1, 0), region(0, 0)),
                SynxBuildVerifier.regionFiles(build, manifest, manifest.bounds()));
        final VerifiedBuild verified = SynxBuildVerifier.open(build, manifest, SynxVectors.expectations(), manifest.bounds());
        assertEquals(4, verified.regionCount());
        assertEquals(manifest.regions().stream().mapToLong(SynxManifest.Region::bytes).sum(), verified.bytesMapped());
        final byte[] checked = assertInstanceOf(SynxSlot.Present.class, verified.region(0, 0).slot(5, 7)).record().packet();

        // Another region of the release written next to region 0,0 and renamed over it
        final Path next = region(0, 0).resolveSibling("r.0.0.synx.next");
        Files.copy(region(-1, 0), next);
        Files.move(next, region(0, 0), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        assertEquals(0, verified.region(0, 0).header().regionX(), "the mapping is the file that was checked");
        assertArrayEquals(checked, assertInstanceOf(SynxSlot.Present.class, verified.region(0, 0).slot(5, 7)).record()
                .packet());
        assertThrows(SynxException.class, () -> SynxBuildVerifier.open(build, manifest, SynxVectors.expectations(),
                manifest.bounds()), "the file at the path now is another one");
    }
}
