package io.github.bearisbug.synaxis.synx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

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
        for (String field : new String[]{"\"map_id\"", "\"build_id\"", "\"content_hash\"", "\"mc_version\"",
                "\"protocol_version\"", "\"registry_digest\"", "\"light_engine_version\"", "\"baker_version\"",
                "\"min_y\"", "\"height\"", "\"bounds\"", "\"payload_sha256\"", "\"chunks\"", "\"farview\"",
                "\"region_id\"", "\"levels\"", "\"source\"", "\"kind\"", "\"bake\"", "\"started_at\"", "\"wall_ms\"",
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

    @Test
    public void unreadableManifestIsRefused() throws Exception {
        final String json = Files.readString(SynxVectors.DIR.resolve(SynxManifest.FILE_NAME));
        assertEquals(SynxError.MANIFEST, assertThrows(SynxException.class, () -> SynxManifest.fromJson("{")).error());
        assertEquals(SynxError.MANIFEST, assertThrows(SynxException.class,
                () -> SynxManifest.fromJson(json.replace("\"protocol_version\"", "\"protocol\""))).error());
        assertEquals(SynxError.MANIFEST, assertThrows(SynxException.class,
                () -> SynxManifest.fromJson(json.replace("\"content_hash\": \"", "\"content_hash\": \"zz"))).error());
    }
}
