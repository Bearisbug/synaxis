package io.github.bearisbug.synaxis.synx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code manifest.json} field names and forms (REQ-M3-002, API-M3-002), held to the frozen vector. */
public class SynxManifestTest {

    @Test
    public void vectorManifestReadsAndWritesBackUnchanged() throws Exception {
        final String json = Files.readString(SynxVectors.DIR.resolve(SynxManifest.FILE_NAME));
        final SynxManifest manifest = SynxManifest.fromJson(json);
        assertEquals(json, manifest.toJson());

        assertEquals("vectors", manifest.mapId());
        assertEquals(776, manifest.protocolVersion());
        assertEquals("26.2", manifest.mcVersion());
        assertEquals(4, manifest.regions().size());
        assertEquals(new SynxManifest.World(0, 256), manifest.world());
        assertEquals(SynxVectors.bounds(), manifest.bounds());
        assertEquals("generator", manifest.source().kind());
        assertNull(manifest.source().dataVersion(), "data_version is only written for Anvil sources");
        assertEquals(1, manifest.farview().size());
        final SynxManifest.FarView pack = manifest.farview().getFirst();
        assertEquals(List.of(-1, 0, 2, 4), List.of(pack.tileX(), pack.tileZ(), pack.baseLevel(), pack.coarseLevel()));
        assertEquals(List.of(8_912_896L, 524_544L), List.of(pack.bytes(), pack.coarseBytes()));
        assertEquals(SynxDigests.hex(SynxDigests.sha256(new byte[]{1})), SynxDigests.hex(pack.sha256()));
        assertEquals(SynxDigests.hex(SynxDigests.sha256(new byte[]{2})), SynxDigests.hex(pack.coarseSha256()));
        assertTrue(!json.contains("\"region_id\"") && !json.contains("\"levels\""), "the fields of the tile layout only");
        for (String field : new String[]{"\"map_id\"", "\"build_id\"", "\"content_hash\"", "\"mc_version\"",
                "\"protocol_version\"", "\"registry_digest\"", "\"light_engine_version\"", "\"baker_version\"",
                "\"min_y\"", "\"height\"", "\"bounds\"", "\"payload_sha256\"", "\"chunks\"", "\"farview\"",
                "\"tile_x\"", "\"tile_z\"", "\"base_level\"", "\"coarse_level\"", "\"coarse_bytes\"", "\"coarse_sha256\"",
                "\"source\"", "\"kind\"", "\"bake\"", "\"started_at\"", "\"wall_ms\"",
                "\"cpu_ms\"", "\"threads\"", "\"jdk\"", "\"verify\"", "\"samples\"", "\"diffs\""}) {
            assertTrue(json.contains(field), field);
        }
    }

    @Test
    public void buildIdCarriesTheContentHashAndTheProtocol() {
        final byte[] hash = SynxDigests.unhex("0123456789abcdef" + "00".repeat(24));
        assertEquals("island-0123456789ab-p776", SynxManifest.buildId("island", hash, 776));
    }

    @Test
    public void anvilSourceKeepsItsDataVersionAndCoverage() throws Exception {
        final String json = Files.readString(SynxVectors.DIR.resolve(SynxManifest.FILE_NAME))
                .replace("\"kind\": \"generator\"", "\"kind\": \"anvil\",\n    \"data_version\": 4903,\n    \"light_coverage\": 1.0");
        final SynxManifest manifest = SynxManifest.fromJson(json);
        assertEquals(4903, manifest.source().dataVersion());
        assertEquals(1.0, manifest.source().lightCoverage());
        assertEquals(json, manifest.toJson());
    }

    /** Each refusal is {@code synx-manifest} and names the field and the value it refuses. */
    @Test
    public void unreadableManifestIsRefused() throws Exception {
        final String json = Files.readString(SynxVectors.DIR.resolve(SynxManifest.FILE_NAME));
        refused("{");
        refused(json.replace("\"protocol_version\"", "\"protocol\""));
        final String hex = refused(json.replace("\"content_hash\": \"", "\"content_hash\": \"zz"));
        assertTrue(hex.contains("content_hash is not hex: zz"), hex);
        final String chunks = refused(json.replace("\"chunks\": 4", "\"chunks\": 4.5"));
        assertTrue(chunks.contains("chunks is not an integer: 4.5"), chunks);
        final String wallMs = refused(json.replace("\"wall_ms\": 1234", "\"wall_ms\": 1234.5"));
        assertTrue(wallMs.contains("wall_ms is not an integer: 1234.5"), wallMs);
        final String bounds = refused(json.replace("\"min_x\": -32", "\"min_x\": 32"));
        assertTrue(bounds.contains("bounds 32,-32,31,31 are empty"), bounds);
    }

    /** The message of the {@code synx-manifest} refusal of {@code json}. */
    private static String refused(String json) {
        final SynxException refused = assertThrows(SynxException.class, () -> SynxManifest.fromJson(json));
        assertEquals(SynxError.MANIFEST, refused.error());
        return refused.getMessage();
    }
}
