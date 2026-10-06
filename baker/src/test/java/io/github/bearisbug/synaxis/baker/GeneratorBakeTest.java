package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.synx.SynxBuildVerifier;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import io.github.bearisbug.synaxis.synx.SynxExpectations;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.github.bearisbug.synaxis.baker.BakeTesting.REGISTRIES;
import static io.github.bearisbug.synaxis.baker.BakeTesting.events;
import static io.github.bearisbug.synaxis.baker.BakeTesting.list;
import static io.github.bearisbug.synaxis.baker.BakeTesting.record;
import static io.github.bearisbug.synaxis.baker.BakeTesting.request;
import static io.github.bearisbug.synaxis.baker.BakeTesting.sections;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The baker on generator sources (REQ-M3-003, API-M3-003, §19 of {@code docs/DESIGN-M3.md}).
 */
public class GeneratorBakeTest {
    /** Chunks 30..33 on both axes: four regions, each with a corner of the map. */
    private static final SynxManifest.Bounds FOUR_REGIONS = new SynxManifest.Bounds(30, 30, 33, 33);

    @TempDir
    Path temp;

    private static GeneratorSource synthetic(SynxManifest.Bounds bounds) {
        return new GeneratorSource(new GeneratorPreset(new SyntheticTerrain(7), 0, SyntheticTerrain.HEIGHT, bounds),
                REGISTRIES);
    }

    private static GeneratorSource source(Generator generator, SynxManifest.Bounds bounds) {
        return new GeneratorSource(new GeneratorPreset(generator, 0, 64, bounds), REGISTRIES);
    }

    /**
     * §19 「组或他人可写的地图文件拒启」, the baker part (M3-S, {@code API-M3-002} 文件信任): the map directory it
     * makes and every directory of the release are 0755, every file 0644, whatever the umask of the baker;
     * a shard refuses a release group or others may write.
     */
    @Test
    public void theReleaseIsReadableByAllAndWrittenByItsOwnerAlone() throws Exception {
        final Baker.Result result = Baker.bake(request("test", synthetic(FOUR_REGIONS), temp, 1));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        final Path mapDir = temp.resolve("maps").resolve("test");
        final List<String> modes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(mapDir)) {
            for (Path path : paths.sorted().toList()) {
                final String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
                if (!mode.equals(Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--")) modes.add(mapDir.relativize(path) + " " + mode);
            }
        }
        assertEquals(List.of(), modes, "what is not 0755 or 0644");
    }

    @Test
    public void bakesAMapAndPublishesItWithItsManifest() throws Exception {
        final Baker.Result result = Baker.bake(request("test", synthetic(FOUR_REGIONS), temp, 4));

        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        final Path build = result.directory();
        assertNotNull(build);
        final SynxManifest manifest = result.manifest();
        assertNotNull(manifest);
        assertEquals(temp.resolve("maps").resolve("test").resolve(manifest.buildId()), build);
        assertEquals(List.of(build), list(temp.resolve("maps").resolve("test")), "no work directory is left behind");
        assertEquals(manifest.toJson(), Files.readString(build.resolve(SynxManifest.FILE_NAME)));

        assertEquals(FOUR_REGIONS, manifest.bounds());
        assertEquals(new SynxManifest.World(0, 256), manifest.world());
        assertEquals(4, manifest.regions().size());
        assertEquals(16, manifest.regions().stream().mapToInt(SynxManifest.Region::chunks).sum());
        assertEquals(new SynxManifest.Source("generator", null, null), manifest.source());
        assertEquals(LightEngine.VERSION, manifest.lightEngineVersion());
        assertEquals(Baker.VERSION, manifest.bakerVersion());
        assertEquals(4, manifest.bake().threads());
        assertTrue(manifest.bake().wallMs() > 0 && manifest.bake().cpuMs() > 0, manifest.toJson());
        assertEquals(new SynxManifest.Verify(16, 0, null), manifest.verify(), "4 samples in each of the 4 regions");
        assertEquals(List.of("0,0"), manifest.farview().stream().map(pack -> pack.tileX() + "," + pack.tileZ()).toList(),
                "the far view pack of the one tile the bounds meet (FarViewBakeTest)");

        // What a shard checks at start-up passes on the whole build
        SynxBuildVerifier.verify(build, SynxExpectations.of(REGISTRIES, Baker.COMPRESSION_THRESHOLD, 0, 256,
                LightEngine.VERSION), FOUR_REGIONS);

        final List<String> events = events(temp);
        assertEquals(2, events.size(), events.toString());
        assertTrue(events.get(0).contains("\"event\":\"map_build_started\"") && events.get(0).contains("\"threads\":4"),
                events.get(0));
        assertTrue(events.get(1).contains("\"event\":\"map_build_finished\"")
                && events.get(1).contains("\"outcome\":\"published\"")
                && events.get(1).contains("\"build_id\":\"" + manifest.buildId() + "\""), events.get(1));
    }

    /**
     * Each region is lit with a ring of one chunk around it, so the light at a region border is the light of the
     * whole map: chunks 31 and 32 lie in different regions, and a lamp in the corner of chunk 31, 31 lights all
     * four regions.
     */
    @Test
    public void theLightCrossesRegionBorders() throws Exception {
        final Generator lamp = unit -> {
            unit.modifier().fillHeight(0, 16, Block.STONE);
            if (unit.absoluteStart().blockX() == 31 * 16 && unit.absoluteStart().blockZ() == 31 * 16) {
                unit.modifier().setBlock(511, 16, 511, Block.GLOWSTONE);
            }
        };
        final Baker.Result result = Baker.bake(request("ring", source(lamp, FOUR_REGIONS), temp, 4));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());

        final GeneratorSource source = source(lamp, FOUR_REGIONS);
        final SourceChunk[] chunks = new SourceChunk[16];
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) chunks[x * 4 + z] = source.read(30 + x, 30 + z);
        }
        final LightResult whole = LightEngine.computeArea(new AreaView(0, 64, 30, 30, 4, 4, chunks), 30, 30, 33, 33);
        int litSections = 0;
        boolean lampLit = false;
        for (int x = 30; x <= 33; x++) {
            for (int z = 30; z <= 33; z++) {
                final LightData light = BakeTesting.packet(record(result.directory(), x, z)).lightData();
                int sky = 0, block = 0;
                for (int s = 0; s < 4; s++) {
                    final byte[] expectedSky = whole.sky(x, s, z), expectedBlock = whole.block(x, s, z);
                    if (light.skyMask().get(s + 1)) {
                        assertArrayEquals(expectedSky, light.skyLight().get(sky++), "sky " + x + "," + s + "," + z);
                        litSections++;
                    } else {
                        assertTrue(light.emptySkyMask().get(s + 1) && Arrays.equals(expectedSky, new byte[2048]));
                    }
                    if (light.blockMask().get(s + 1)) {
                        assertArrayEquals(expectedBlock, light.blockLight().get(block++), "block " + x + "," + s + "," + z);
                        if (x == 32 && z == 32) lampLit = true;
                    } else {
                        assertTrue(light.emptyBlockMask().get(s + 1) && Arrays.equals(expectedBlock, new byte[2048]));
                    }
                }
            }
        }
        assertTrue(litSections >= 16, litSections + " sections with sky light");
        assertTrue(lampLit, "the lamp in region 0, 0 lights chunk 32, 32 in region 1, 1");
    }

    /** §19: the generator hands over direct palettes; what the baker writes is single valued or indirect. */
    @Test
    public void everyBakedSectionIsSingleValuedOrIndirect() throws Exception {
        final Baker.Result result = Baker.bake(request("palettes", synthetic(FOUR_REGIONS), temp, 2));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());

        int single = 0, indirect = 0;
        for (int x = 30; x <= 33; x++) {
            for (int z = 30; z <= 33; z++) {
                final List<ChunkData.Section> sections = sections(record(result.directory(), x, z));
                assertEquals(16, sections.size());
                for (ChunkData.Section section : sections) {
                    final int bits = section.blockStates().bitsPerEntry();
                    assertTrue(bits <= Palette.BLOCK_PALETTE_MAX_BITS, "chunk " + x + "," + z + ": " + bits + " bits");
                    if (bits == 0) single++;
                    else indirect++;
                }
            }
        }
        assertTrue(single > 0 && indirect > 0, single + " single, " + indirect + " indirect");
    }

    /** A section with more states than an indirect palette holds fails its region: exit code 3. */
    @Test
    public void aSectionThatNeedsADirectPaletteFailsTheBake() throws Exception {
        final Generator manyStates = unit -> unit.subdivide().getFirst().modifier()
                .setAllRelative((x, y, z) -> Block.fromStateId(1 + (y << 8 | z << 4 | x) % 300));
        final Baker.Result result = Baker.bake(request("direct", source(manyStates, new SynxManifest.Bounds(0, 0, 0, 0)),
                temp, 1));

        assertEquals(Baker.EXIT_REGION, result.exitCode());
        assertTrue(result.message().contains("chunk 0, 0 section 0 holds 300 block states"), result.message());
        assertTrue(events(temp).get(1).contains("\"outcome\":\"failed\""), events(temp).toString());
        assertNotNull(result.directory(), "a failed bake keeps its work directory for inspection");
    }

    /** §19: 1 thread and 9 threads over a map of 3 × 3 regions make the same release. */
    @Test
    public void aParallelBakeEqualsASerialOne() throws Exception {
        final SynxManifest.Bounds nineRegions = new SynxManifest.Bounds(31, 31, 64, 64);
        final Baker.Result serial = Baker.bake(request("same", synthetic(nineRegions), temp.resolve("serial"), 1));
        final Baker.Result parallel = Baker.bake(request("same", synthetic(nineRegions), temp.resolve("parallel"), 9));

        assertEquals(Baker.EXIT_PUBLISHED, serial.exitCode(), serial.message());
        assertEquals(Baker.EXIT_PUBLISHED, parallel.exitCode(), parallel.message());
        assertEquals(9, serial.manifest().regions().size());
        assertArrayEquals(serial.manifest().contentHash(), parallel.manifest().contentHash());
        assertEquals(serial.manifest().buildId(), parallel.manifest().buildId());
        for (SynxManifest.Region region : serial.manifest().regions()) {
            final String name = SynxFormat.regionFileName(region.regionX(), region.regionZ());
            assertArrayEquals(Files.readAllBytes(serial.directory().resolve("regions").resolve(name)),
                    Files.readAllBytes(parallel.directory().resolve("regions").resolve(name)), name);
        }
    }

    @Test
    public void bakingTheSameBuildAgainExitsFiveAndLeavesTheFirst() throws Exception {
        final SynxManifest.Bounds one = new SynxManifest.Bounds(0, 0, 1, 1);
        final Baker.Result first = Baker.bake(request("twice", synthetic(one), temp, 1));
        final Baker.Result second = Baker.bake(request("twice", synthetic(one), temp, 1));

        assertEquals(Baker.EXIT_PUBLISHED, first.exitCode(), first.message());
        assertEquals(Baker.EXIT_EXISTS, second.exitCode(), second.message());
        assertEquals(List.of(first.directory()), list(temp.resolve("maps").resolve("twice")));
    }

    /** §11.2: a light engine version outside the verified set is refused before a single chunk is read. */
    @Test
    public void anUnverifiedLightEngineVersionExitsSixAndWritesNothing() throws Exception {
        final Baker.Request request = new Baker.Request("unverified", synthetic(FOUR_REGIONS), temp.resolve("maps"), 1,
                temp.resolve("logs"), REGISTRIES, _ -> null, Set.of(LightEngine.VERSION + 1), 4);
        final Baker.Result result = Baker.bake(request);

        assertEquals(Baker.EXIT_LIGHT_VERSION, result.exitCode());
        assertFalse(Files.exists(temp.resolve("maps")));
        assertFalse(Files.exists(temp.resolve("logs")), "no event either");
    }

    @Test
    public void theVerifiedLightVersionsIncludeTheCurrentOne() {
        assertTrue(LightVersions.verified().contains(LightEngine.VERSION), LightVersions.verified().toString());
    }

    @Test
    public void aGeneratorThatForksIntoAnotherChunkFailsItsRegion() throws Exception {
        final Generator forking = unit -> unit.fork(setter -> setter.setBlock(unit.absoluteStart().add(20, 1, 0), Block.STONE));
        final Baker.Result result = Baker.bake(request("fork", source(forking, new SynxManifest.Bounds(0, 0, 0, 0)), temp, 1));

        assertEquals(Baker.EXIT_REGION, result.exitCode());
        assertTrue(result.message().contains("forked"), result.message());
    }

    /** The sampled re-bake catches a source that does not give the same chunk twice. */
    @Test
    public void aSourceThatChangesBetweenReadsFailsTheVerification() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final Generator drifting = unit -> unit.modifier().fillHeight(0, 1 + calls.incrementAndGet() % 7, Block.STONE);
        final Baker.Result result = Baker.bake(request("drift", source(drifting, new SynxManifest.Bounds(0, 0, 0, 0)),
                temp, 1));

        assertEquals(Baker.EXIT_VERIFY, result.exitCode(), result.message());
        assertEquals(new SynxManifest.Verify(1, 1, null), result.manifest().verify());
        assertTrue(events(temp).get(1).contains("\"verify_diffs\":1"), events(temp).toString());
    }

    /** The M2 map generator, found through its provider on the class path, as {@code generator:rts-128:<seed>}. */
    @Test
    public void theM2GeneratorBakesFromTheCommandLine() throws Exception {
        final int exit = BakerMain.run(new String[]{"bake", "--map-id", "rts", "--source", "generator:rts-128:21075",
                "--out", temp.resolve("maps").toString(), "--threads", "2", "--logs", temp.resolve("logs").toString()});

        assertEquals(Baker.EXIT_PUBLISHED, exit);
        final Path build = list(temp.resolve("maps").resolve("rts")).getFirst();
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(build.resolve(SynxManifest.FILE_NAME)));
        assertEquals(new SynxManifest.Bounds(0, 0, 7, 7), manifest.bounds());
        assertEquals(new SynxManifest.World(0, 64), manifest.world());

        // Segment ②: the ground is 40 layers of stone, a unit stands at y 40 unless a pillar is in the way
        final SynxChunkRecord record = record(build, 0, 0);
        final ByteBuffer navigation = ByteBuffer.wrap(record.navigation()).order(ByteOrder.LITTLE_ENDIAN);
        int flat = 0;
        for (int column = 0; column < 256; column++) {
            final short standing = navigation.getShort(32 + column * 2);
            assertTrue(standing == 40 || standing == 43, "column " + column + " stands at " + standing);
            if (standing == 40) flat++;
        }
        assertTrue(flat > 200, flat + " flat columns");
    }

    @Test
    public void aSourceThatDoesNotExistExitsTwoAndWritesNothing() {
        final int exit = BakerMain.run(new String[]{"bake", "--map-id", "none", "--source",
                "anvil:" + temp.resolve("nowhere"), "--out", temp.resolve("maps").toString(),
                "--logs", temp.resolve("logs").toString()});

        assertEquals(Baker.EXIT_SOURCE, exit);
        assertFalse(Files.exists(temp.resolve("maps")));
    }
}
