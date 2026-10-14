package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.baker.FarViewAssembler.Replacement;
import io.github.bearisbug.synaxis.baker.anvil.HiddenArea;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.bearisbug.synaxis.baker.BakeTesting.REGISTRIES;
import static io.github.bearisbug.synaxis.baker.BakeTesting.events;
import static io.github.bearisbug.synaxis.baker.BakeTesting.list;
import static io.github.bearisbug.synaxis.baker.BakeTesting.request;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far view packs a bake writes ({@code docs/DESIGN-M3.md} {@code API-M3-014}, {@code API-M3-003}; §19 of
 * {@code REQ-M3-024}): one per tile meeting the bounds, listed in the manifest, the same for any number of
 * threads, hidden areas replaced on both layers, and refused by {@code verify} when they do not match.
 */
public class FarViewBakeTest {
    /** The synthetic 4k × 4k test map, one whole tile. */
    private static final SynxManifest.Bounds TILE = new SynxManifest.Bounds(0, 0, 255, 255);
    /** 13 × 16 chunks across the corner of four tiles. */
    private static final SynxManifest.Bounds FOUR_TILES = new SynxManifest.Bounds(250, -8, 262, 7);

    @TempDir
    static Path shared;
    private static Baker.Result tile;

    @TempDir
    Path temp;

    private static GeneratorSource synthetic(SynxManifest.Bounds bounds) {
        return new GeneratorSource(new GeneratorPreset(new SyntheticTerrain(7), 0, SyntheticTerrain.HEIGHT, bounds),
                REGISTRIES);
    }

    private static GeneratorSource source(Generator generator, SynxManifest.Bounds bounds) {
        return new GeneratorSource(new GeneratorPreset(generator, 0, 64, bounds), REGISTRIES);
    }

    @BeforeAll
    static void bakeTheTile() {
        tile = Baker.bake(request("tile", synthetic(TILE), shared, 4));
    }

    private static SynxManifest manifest(Path build) throws Exception {
        return SynxManifest.fromJson(Files.readString(build.resolve(SynxManifest.FILE_NAME)));
    }

    private static FarViewPack.Reader pack(Path build, int tileX, int tileZ) throws Exception {
        return FarViewPack.Reader.read(Files.readAllBytes(build.resolve("farview").resolve("t." + tileX + "." + tileZ + ".hfp")));
    }

    /** An entry of {@code farview[]} as text, its digests in hex. */
    private static String text(SynxManifest.FarView entry) {
        return entry.tileX() + "," + entry.tileZ() + " " + entry.baseLevel() + "/" + entry.coarseLevel() + " " + entry.bytes()
                + " " + SynxDigests.hex(entry.sha256()) + " " + entry.coarseBytes() + " " + SynxDigests.hex(entry.coarseSha256());
    }

    /**
     * §19 「高度场包随地图版本产出」: the 4k × 4k test map has one pack, its tile's, every block of both layers
     * present, listed in the manifest with its size and digest and those of its coarse prefix.
     */
    @Test
    public void theTestMapHasOnePackListedInItsManifest() throws Exception {
        assertEquals(Baker.EXIT_PUBLISHED, tile.exitCode(), tile.message());
        final Path build = tile.directory();
        assertNotNull(build);
        assertEquals(List.of(build.resolve("farview").resolve("t.0.0.hfp")), list(build.resolve("farview")));
        final SynxManifest manifest = manifest(build);
        assertEquals(1, manifest.farview().size());
        final SynxManifest.FarView entry = manifest.farview().getFirst();
        assertEquals(List.of(0, 0, 2, 4), List.of(entry.tileX(), entry.tileZ(), entry.baseLevel(), entry.coarseLevel()));

        final byte[] pack = Files.readAllBytes(build.resolve("farview").resolve("t.0.0.hfp"));
        assertEquals(pack.length, entry.bytes());
        assertArrayEquals(SynxDigests.sha256(pack), entry.sha256());
        final ByteBuffer header = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("SHFP", new String(pack, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(1, header.getShort(4), "format_version");
        assertEquals(0, header.getShort(6), "flags");
        final FarViewPack.Reader reader = FarViewPack.Reader.read(pack);
        assertEquals(new FarViewPack.Header(2, 4, 0, 0, 0, 256, Block.statesCount(), 776, entry.coarseBytes()), reader.header());
        assertArrayEquals(SynxDigests.sha256(Arrays.copyOf(pack, (int) entry.coarseBytes())), entry.coarseSha256());
        assertEquals(16, reader.blocks(Layer.COARSE).size());
        assertEquals(256, reader.blocks(Layer.FINE).size());
        reader.verify();

        assertEquals(2, manifest.bakerVersion());
        assertEquals(Baker.VERSION, manifest.bakerVersion());
        final String finished = events(shared).getLast();
        assertTrue(finished.contains("\"event\":\"map_build_finished\"") && finished.contains("\"farview_bytes\":" + pack.length),
                finished);
        assertTrue(tile.message().contains("1 far view pack(s), " + pack.length + " bytes, assembled in "), tile.message());
    }

    /**
     * §19 「地图跨瓦片时每个相交的瓦片一个包」: the four tiles the bounds meet have a pack each, in tile order;
     * a block wholly outside the bounds is absent, a sample outside them an empty column of biome 0.
     */
    @Test
    public void aMapAcrossTilesHasAPackForEachTileItMeets() throws Exception {
        final Baker.Result result = Baker.bake(request("four", synthetic(FOUR_TILES), temp, 2));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        final Path build = result.directory();
        assertEquals(List.of("t.0.-1.hfp", "t.0.0.hfp", "t.1.-1.hfp", "t.1.0.hfp"),
                list(build.resolve("farview")).stream().map(file -> file.getFileName().toString()).sorted().toList());
        final SynxManifest manifest = manifest(build);
        assertEquals(List.of("0,-1", "0,0", "1,-1", "1,0"),
                manifest.farview().stream().map(entry -> entry.tileX() + "," + entry.tileZ()).toList());

        for (SynxManifest.FarView entry : manifest.farview()) {
            final FarViewPack.Reader reader = pack(build, entry.tileX(), entry.tileZ());
            int inside = 0;
            for (Layer layer : Layer.values()) {
                final int level = layer == Layer.COARSE ? 4 : 2, side = FarViewPack.blocksPerSide(level);
                final int samplesPerChunk = 16 >> level;
                final int chunksPerBlock = FarViewPack.BLOCK_SIDE / samplesPerChunk;
                final List<String> present = reader.blocks(layer).stream().map(block -> block.bx() + "," + block.bz()).toList();
                for (int bz = 0; bz < side; bz++) {
                    for (int bx = 0; bx < side; bx++) {
                        final int chunkX = (entry.tileX() << 8) + bx * chunksPerBlock;
                        final int chunkZ = (entry.tileZ() << 8) + bz * chunksPerBlock;
                        final boolean meets = chunkX <= FOUR_TILES.maxX() && chunkX + chunksPerBlock - 1 >= FOUR_TILES.minX()
                                && chunkZ <= FOUR_TILES.maxZ() && chunkZ + chunksPerBlock - 1 >= FOUR_TILES.minZ();
                        assertEquals(meets, present.contains(bx + "," + bz), layer + " block " + bx + "," + bz);
                    }
                }
                for (FarViewPack.Block block : reader.blocks(layer)) {
                    final List<Sample> samples = reader.samples(block);
                    for (int i = 0; i < samples.size(); i++) {
                        final int chunkX = (entry.tileX() << 8) + (block.bx() * FarViewPack.BLOCK_SIDE + i % 64) / samplesPerChunk;
                        final int chunkZ = (entry.tileZ() << 8) + (block.bz() * FarViewPack.BLOCK_SIDE + i / 64) / samplesPerChunk;
                        final boolean in = chunkX >= FOUR_TILES.minX() && chunkX <= FOUR_TILES.maxX()
                                && chunkZ >= FOUR_TILES.minZ() && chunkZ <= FOUR_TILES.maxZ();
                        if (in) {
                            inside++;
                            assertTrue(samples.get(i).top() >= 0, "the synthetic terrain has ground everywhere");
                        } else {
                            assertEquals(Sample.EMPTY, samples.get(i), layer + " sample " + i + " of " + block);
                        }
                    }
                }
            }
            assertTrue(inside > 0, entry.toString());
        }
    }

    /** §19 「同一输入的高度场包逐字节相同」: 1 thread and 4 threads bake the same build and the same packs. */
    @Test
    public void oneThreadAndFourBakeTheSamePacks() throws Exception {
        final Baker.Result serial = Baker.bake(request("same", synthetic(FOUR_TILES), temp.resolve("serial"), 1));
        final Baker.Result parallel = Baker.bake(request("same", synthetic(FOUR_TILES), temp.resolve("parallel"), 4));
        assertEquals(Baker.EXIT_PUBLISHED, serial.exitCode(), serial.message());
        assertEquals(Baker.EXIT_PUBLISHED, parallel.exitCode(), parallel.message());

        assertEquals(serial.manifest().buildId(), parallel.manifest().buildId());
        assertEquals(serial.manifest().farview().stream().map(FarViewBakeTest::text).toList(),
                parallel.manifest().farview().stream().map(FarViewBakeTest::text).toList());
        assertEquals(4, serial.manifest().farview().size());
        for (Path file : list(serial.directory().resolve("farview"))) {
            assertArrayEquals(Files.readAllBytes(file),
                    Files.readAllBytes(parallel.directory().resolve("farview").resolve(file.getFileName())), file.toString());
        }
    }

    /**
     * A copy of the test map to run {@code verify --build} on, in a directory named after its build: its regions
     * linked, its manifest and packs copied.
     */
    private static Path linkedCopy(Path build, Path copy) throws IOException {
        Files.createDirectories(copy.resolve("regions"));
        Files.createDirectories(copy.resolve("farview"));
        for (Path region : list(build.resolve("regions"))) {
            Files.createLink(copy.resolve("regions").resolve(region.getFileName()), region);
        }
        for (Path pack : list(build.resolve("farview"))) {
            Files.copy(pack, copy.resolve("farview").resolve(pack.getFileName()));
        }
        Files.copy(build.resolve(SynxManifest.FILE_NAME), copy.resolve(SynxManifest.FILE_NAME));
        return copy;
    }

    private record Run(int exitCode, String err) {
    }

    private static Run verify(Path build) {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream stderr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return new Run(BakerMain.run(new String[]{"verify", "--build", build.toString()}), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(stderr);
        }
    }

    private static void flip(Path file, int at) throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        bytes[at] ^= 0x01;
        Files.write(file, bytes);
    }

    /**
     * §19 「校验拦下与清单或区域不符的高度场包」, the command line half: one byte of the pack, or one bit of the
     * manifest's coarse_sha256, and {@code verify} exits with 4 naming the pack.
     */
    @Test
    public void verifyNamesAPackOrACoarsePrefixThatDoesNotMatchTheManifest() throws Exception {
        final Path copy = linkedCopy(tile.directory(), temp.resolve("copy").resolve(tile.directory().getFileName()));
        assertEquals(new Run(Baker.EXIT_PUBLISHED, ""), verify(copy));

        final Path pack = copy.resolve("farview").resolve("t.0.0.hfp");
        flip(pack, (int) Files.size(pack) / 2);
        final Run changedByte = verify(copy);
        assertEquals(Baker.EXIT_VERIFY, changedByte.exitCode());
        assertTrue(changedByte.err().contains("farview/t.0.0.hfp: the SHA-256 of the file does not match the manifest"),
                changedByte.err());
        flip(pack, (int) Files.size(pack) / 2);

        final SynxManifest manifest = manifest(copy);
        final SynxManifest.FarView entry = manifest.farview().getFirst();
        final byte[] coarseSha256 = entry.coarseSha256();
        coarseSha256[0] ^= 0x01;
        Files.writeString(copy.resolve(SynxManifest.FILE_NAME), new SynxManifest(manifest.mapId(), manifest.buildId(),
                manifest.contentHash(), manifest.mcVersion(), manifest.protocolVersion(), manifest.registryDigest(),
                manifest.lightEngineVersion(), manifest.bakerVersion(), manifest.world(), manifest.bounds(), manifest.regions(),
                List.of(new SynxManifest.FarView(0, 0, 2, 4, entry.bytes(), entry.sha256(), entry.coarseBytes(), coarseSha256)),
                manifest.source(), manifest.bake(), manifest.verify()).toJson());
        final Run changedDigest = verify(copy);
        assertEquals(Baker.EXIT_VERIFY, changedDigest.exitCode());
        assertTrue(changedDigest.err().contains("farview/t.0.0.hfp: the coarse prefix does not match the manifest's coarse_sha256"),
                changedDigest.err());
    }

    private static void rewrite(Path build, int bakerVersion, List<SynxManifest.FarView> farview) throws Exception {
        final SynxManifest manifest = manifest(build);
        Files.writeString(build.resolve(SynxManifest.FILE_NAME), new SynxManifest(manifest.mapId(), manifest.buildId(),
                manifest.contentHash(), manifest.mcVersion(), manifest.protocolVersion(), manifest.registryDigest(),
                manifest.lightEngineVersion(), bakerVersion, manifest.world(), manifest.bounds(), manifest.regions(), farview,
                manifest.source(), manifest.bake(), manifest.verify()).toJson());
    }

    /**
     * A build of a baker before M3-G has no far view packs and an empty {@code farview[]}, and a shard mounts it
     * with the far view off ({@code API-M3-002}), so {@code verify} passes it; the same build with baker_version
     * 2 is refused for the packs it lacks. The earlier build is a fresh one without its packs, baker_version 1 in
     * its manifest and in the header of its region.
     */
    @Test
    public void verifyPassesABuildOfAnEarlierBakerWithoutPacks() throws Exception {
        final Baker.Result result = Baker.bake(request("earlier", source(unit -> unit.modifier().fillHeight(0, 40, Block.STONE),
                new SynxManifest.Bounds(0, 0, 1, 1)), temp, 1));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        final Path build = result.directory();
        Baker.deleteTree(build.resolve("farview"));

        rewrite(build, 2, List.of());
        final Run current = verify(build);
        assertEquals(Baker.EXIT_VERIFY, current.exitCode());
        assertTrue(current.err().contains("farview[] lists the tiles [], the bounds meet [0,0]"), current.err());

        for (Path region : list(build.resolve("regions"))) {
            final byte[] bytes = Files.readAllBytes(region);
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(162, (short) 1);
            Files.write(region, bytes);
        }
        rewrite(build, 1, List.of());
        assertEquals(new Run(Baker.EXIT_PUBLISHED, ""), verify(build));
    }

    /** A chunk's fine samples as its pack holds them, each biome turned back into its registry id. */
    private static List<Sample> packed(Path build, int chunkX, int chunkZ) throws Exception {
        final int tileX = FarViewPack.tile(chunkX), tileZ = FarViewPack.tile(chunkZ);
        final FarViewPack.Reader reader = pack(build, tileX, tileZ);
        final int x = (chunkX - (tileX << 8)) * 4, z = (chunkZ - (tileZ << 8)) * 4;
        for (FarViewPack.Block block : reader.blocks(Layer.FINE)) {
            if (block.bx() != x / 64 || block.bz() != z / 64) continue;
            final List<Sample> samples = reader.samples(block);
            final List<Sample> fine = new ArrayList<>();
            for (int sz = 0; sz < 4; sz++) {
                for (int sx = 0; sx < 4; sx++) {
                    final Sample sample = samples.get((z % 64 + sz) * 64 + x % 64 + sx);
                    fine.add(new Sample(sample.groundState(), sample.fluidState(), sample.groundY(), sample.fluidBottom(),
                            sample.fluidTop(), REGISTRIES.biome().getId(RegistryKey.unsafeOf(reader.biomes().get(sample.biome())))));
                }
            }
            return fine;
        }
        throw new AssertionError("no fine block for chunk " + chunkX + "," + chunkZ);
    }

    /**
     * The sampled half of {@code verify} holds the pack and the inflated blocks of one tile at a time, so what it
     * holds does not grow with the map: after a chunk of tile 0,-1 and then one of tile 1,0 it holds tile 1,0
     * and the fine and the coarse block of that chunk.
     */
    @Test
    public void theSampledCheckHoldsOneTileAtATime() throws Exception {
        final Baker.Result result = Baker.bake(request("held", synthetic(FOUR_TILES), temp, 2));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        final Path build = result.directory();
        final SynxManifest manifest = manifest(build);
        final Baker.SampledFarView check = new Baker.SampledFarView(build, new FarViewAssembler(FOUR_TILES, 0,
                SyntheticTerrain.HEIGHT, manifest.protocolVersion(), List.of(), REGISTRIES.biome()));
        for (int[] chunk : List.of(new int[]{255, -1}, new int[]{256, 0})) {
            final String name = "chunk " + chunk[0] + "," + chunk[1];
            assertTrue(check.matches(chunk[0], chunk[1], packed(build, chunk[0], chunk[1])), name);
            assertEquals(List.of(1, 2), List.of(check.tilesHeld(), check.blocksHeld()), "tiles and blocks held after " + name);
        }
    }

    /**
     * §19 「校验拦下…」, the bake half: a fine sample or the coarse sample of the one chunk the verification
     * samples is changed before the pack is written, so the manifest's digests match the changed pack; the
     * sample bakes again into something else, exit code 4, the work directory kept.
     */
    @Test
    public void thePrePublishVerificationCatchesAChangedFineOrCoarseSample() throws Exception {
        final SynxManifest.Bounds oneChunk = new SynxManifest.Bounds(0, 0, 0, 0);
        for (Layer layer : Layer.values()) {
            final Baker.Result result = Baker.bake(request("changed", source(unit -> unit.modifier().fillHeight(0, 40, Block.STONE),
                    oneChunk), temp.resolve(layer.name()), 1), (_, _, coarse, fine) -> {
                final long[] grid = layer == Layer.COARSE ? coarse : fine;
                final Sample sample = FarViewAssembler.sample(grid[0]);
                grid[0] = new Sample(sample.groundState(), 0, sample.groundY() - 1, 0, 0, sample.biome()).packed();
            });

            assertEquals(Baker.EXIT_VERIFY, result.exitCode(), result.message());
            assertEquals("1 of 1 sampled chunks differ; first chunk 0, 0 (far view samples)", result.message(), layer.name());
            final SynxManifest manifest = result.manifest();
            assertNotNull(manifest);
            assertEquals(new SynxManifest.Verify(1, 1, null), manifest.verify(), layer.name());
            final Path work = result.directory();
            assertNotNull(work);
            assertTrue(work.getFileName().toString().startsWith(".baking-") && Files.isDirectory(work), work.toString());
            assertNull(Baker.checkFarView(work.resolve(manifest.buildId()), manifest), "the digests match the changed pack");
            final FarViewPack.Reader pack = pack(work.resolve(manifest.buildId()), 0, 0);
            assertEquals(39 - 1, pack.samples(pack.blocks(layer).getFirst()).getFirst().groundY(), "the changed sample");
        }
    }

    /** A generator source with the hidden areas an Anvil source collects from its {@code synaxis:hidden} markers. */
    private record Hiding(GeneratorSource source, List<HiddenArea> hidden) implements BakeSource {
        @Override
        public String kind() {
            return source.kind();
        }

        @Override
        public int minY() {
            return source.minY();
        }

        @Override
        public int height() {
            return source.height();
        }

        @Override
        public SynxManifest.Bounds bounds() {
            return source.bounds();
        }

        @Override
        public @Nullable Integer dataVersion() {
            return null;
        }

        @Override
        public @Nullable Double lightCoverage() {
            return null;
        }

        @Override
        public void checkGate(int threads) {
        }

        @Override
        public List<HiddenArea> hiddenAreas() {
            return hidden;
        }

        @Override
        public SourceChunk read(int chunkX, int chunkZ) {
            return source.read(chunkX, chunkZ);
        }

        @Override
        public void close() {
        }
    }

    /** Stone up to a height that changes from column to column, at least 10. */
    private static int ground(int x, int z) {
        return 10 + Math.floorMod(x * 7 + z * 3, 13);
    }

    /**
     * §19 「隐藏区域在高度场包里被替换」 with ring samples that have ground: on 4 × 4 chunks a hidden area at
     * blocks 20,20 to 35,27 with a gold tower at 24,24 in it. Its fine samples 5..8 × 5..6 and its chunks 1..2 × 1
     * become stone of plains at the lowest top of their ring on their own layer; every other coarse sample is the
     * one {@link FarViewPack#coarsen} takes from the chunk's fine samples, and the tower shows nowhere. The
     * verification samples every chunk and passes.
     */
    @Test
    public void aHiddenAreaIsReplacedOnBothLayersByTheLowestTopOfItsRing() throws Exception {
        final SynxManifest.Bounds bounds = new SynxManifest.Bounds(0, 0, 3, 3);
        final Generator terrain = unit -> {
            final int originX = unit.absoluteStart().blockX(), originZ = unit.absoluteStart().blockZ();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y <= ground(originX + x, originZ + z); y++) {
                        unit.modifier().setBlock(originX + x, y, originZ + z, Block.STONE);
                    }
                }
            }
            if (originX == 16 && originZ == 16) {
                for (int y = 0; y <= 60; y++) unit.modifier().setBlock(24, y, 24, Block.GOLD_BLOCK);
            }
        };
        final BakeSource source = new Hiding(source(terrain, bounds), List.of(new HiddenArea(20, 20, 35, 27)));
        final Baker.Result result = Baker.bake(new Baker.Request("hidden", source, temp.resolve("maps"), 2,
                temp.resolve("logs"), REGISTRIES, Map.<String, BlockHandler>of()::get, Set.of(LightEngine.VERSION), 16));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        assertEquals(new SynxManifest.Verify(16, 0, null), result.manifest().verify());

        final FarViewPack.Reader reader = pack(result.directory(), 0, 0);
        final List<Sample> fine = reader.samples(reader.blocks(Layer.FINE).getFirst());
        final List<Sample> coarse = reader.samples(reader.blocks(Layer.COARSE).getFirst());
        final int plains = reader.biomes().indexOf("minecraft:plains");

        // Fine: the ring is samples 4..9 × 4..7 around 5..8 × 5..6
        int fineRing = Integer.MAX_VALUE;
        for (int x = 4; x <= 9; x++) {
            for (int z = 4; z <= 7; z++) {
                if (x >= 5 && x <= 8 && z >= 5 && z <= 6) continue;
                fineRing = Math.min(fineRing, Math.max(0, fine.get(z * 64 + x).top()));
            }
        }
        assertTrue(fineRing >= 10, "the ring has ground: " + fineRing);
        for (int x = 5; x <= 8; x++) {
            for (int z = 5; z <= 6; z++) {
                assertEquals(new Sample(Block.STONE.stateId(), 0, fineRing, 0, 0, plains), fine.get(z * 64 + x), x + "," + z);
            }
        }

        // Coarse: the ring is chunks 0..3 × 0..2 around 1..2 × 1
        int coarseRing = Integer.MAX_VALUE;
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 2; z++) {
                if (z == 1 && (x == 1 || x == 2)) continue;
                coarseRing = Math.min(coarseRing, Math.max(0, coarse.get(z * 64 + x).top()));
            }
        }
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                final Sample expected;
                if (z == 1 && (x == 1 || x == 2)) {
                    expected = new Sample(Block.STONE.stateId(), 0, coarseRing, 0, 0, plains);
                } else {
                    final List<Sample> chunk = new ArrayList<>();
                    for (int sz = 0; sz < 4; sz++) {
                        for (int sx = 0; sx < 4; sx++) chunk.add(fine.get((z * 4 + sz) * 64 + x * 4 + sx));
                    }
                    expected = FarViewPack.coarsen(2, chunk);
                }
                assertEquals(expected, coarse.get(z * 64 + x), "chunk " + x + "," + z);
            }
        }
        for (List<Sample> layer : List.of(fine, coarse)) {
            assertFalse(layer.stream().anyMatch(sample -> sample.groundState() == Block.GOLD_BLOCK.stateId()),
                    "the tower in the hidden area shows on no layer");
        }
    }

    /**
     * The hidden rule on one grid: an area's ring leaves out the samples of every area, a sample of two areas
     * takes the lower height, and an area whose ring lies inside other areas has height 0.
     */
    @Test
    public void theHiddenRuleTakesTheLowestTopOfTheRingLeftByEveryArea() throws Exception {
        // On level 2 a sample is 4 blocks: A is samples 2..3 × 2..3, B sample 4 × 2 right next to it
        final FarViewAssembler.CellTop top = (x, z) -> x == 4 && z == 2 ? 1 : 20 + x + 10 * z;
        final List<Replacement> beside = FarViewAssembler.hide(List.of(new HiddenArea(8, 8, 15, 15),
                new HiddenArea(16, 8, 19, 11)), 2, top);
        assertEquals(List.of(new Replacement(2, 2, 3, 3, 31), new Replacement(4, 2, 4, 2, 33)), beside,
                "B's 1 is not in A's ring, A's 3,2 and 3,3 not in B's");
        assertEquals(31, FarViewAssembler.replacedHeight(beside, 3, 3));
        assertEquals(-1, FarViewAssembler.replacedHeight(beside, 5, 5));

        // C is samples 3..5 × 3..5, over A's 3,3
        final FarViewAssembler.CellTop slope = (x, z) -> 20 + x + 10 * z;
        final List<Replacement> over = FarViewAssembler.hide(List.of(new HiddenArea(8, 8, 15, 15),
                new HiddenArea(12, 12, 23, 23)), 2, slope);
        assertEquals(List.of(new Replacement(2, 2, 3, 3, 31), new Replacement(3, 3, 5, 5, 44)), over);
        assertEquals(31, FarViewAssembler.replacedHeight(over, 3, 3));
        assertEquals(44, FarViewAssembler.replacedHeight(over, 5, 5));

        final List<Replacement> inside = FarViewAssembler.hide(List.of(new HiddenArea(0, 0, 63, 63),
                new HiddenArea(20, 20, 23, 23)), 2, top);
        assertEquals(0, inside.get(1).height());
        assertEquals(0, FarViewAssembler.replacedHeight(inside, 5, 5));
    }

    /**
     * §28.1, API-M3-014 隐藏区域: a sample outside the bounds is no ring sample. On 4 × 4 chunks with ground
     * everywhere a hidden area at blocks 0,20 to 7,27 lies on the west edge: its fine samples 0..1 × 5..6 and its
     * chunk 0 × 1 take the lowest top of the ring inside the bounds on their own layer, not the 0 of the empty
     * columns past the edge. The verification takes the same ring, samples every chunk and passes.
     */
    @Test
    public void aHiddenAreaAtTheEdgeOfTheMapTakesItsRingInsideTheBounds() throws Exception {
        final SynxManifest.Bounds bounds = new SynxManifest.Bounds(0, 0, 3, 3);
        final Generator terrain = unit -> {
            final int originX = unit.absoluteStart().blockX(), originZ = unit.absoluteStart().blockZ();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y <= ground(originX + x, originZ + z); y++) {
                        unit.modifier().setBlock(originX + x, y, originZ + z, Block.STONE);
                    }
                }
            }
        };
        final BakeSource source = new Hiding(source(terrain, bounds), List.of(new HiddenArea(0, 20, 7, 27)));
        final Baker.Result result = Baker.bake(new Baker.Request("edge", source, temp.resolve("maps"), 2,
                temp.resolve("logs"), REGISTRIES, Map.<String, BlockHandler>of()::get, Set.of(LightEngine.VERSION), 16));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        assertEquals(new SynxManifest.Verify(16, 0, null), result.manifest().verify());

        final FarViewPack.Reader reader = pack(result.directory(), 0, 0);
        final List<Sample> fine = reader.samples(reader.blocks(Layer.FINE).getFirst());
        final List<Sample> coarse = reader.samples(reader.blocks(Layer.COARSE).getFirst());
        final int plains = reader.biomes().indexOf("minecraft:plains");

        // Fine: the ring inside the bounds is samples 0..2 × 4..7 around 0..1 × 5..6
        int fineRing = Integer.MAX_VALUE;
        for (int x = 0; x <= 2; x++) {
            for (int z = 4; z <= 7; z++) {
                if (x <= 1 && z >= 5 && z <= 6) continue;
                fineRing = Math.min(fineRing, fine.get(z * 64 + x).top());
            }
        }
        assertTrue(fineRing >= 10, "the ring inside the bounds has ground: " + fineRing);
        for (int x = 0; x <= 1; x++) {
            for (int z = 5; z <= 6; z++) {
                assertEquals(new Sample(Block.STONE.stateId(), 0, fineRing, 0, 0, plains), fine.get(z * 64 + x), x + "," + z);
            }
        }

        // Coarse: the ring inside the bounds is chunks 0..1 × 0..2 around 0 × 1
        int coarseRing = Integer.MAX_VALUE;
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 2; z++) {
                if (x == 0 && z == 1) continue;
                coarseRing = Math.min(coarseRing, coarse.get(z * 64 + x).top());
            }
        }
        assertTrue(coarseRing >= 10, "the ring inside the bounds has ground: " + coarseRing);
        assertEquals(new Sample(Block.STONE.stateId(), 0, coarseRing, 0, 0, plains), coarse.get(64));
    }
}
