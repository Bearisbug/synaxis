package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static io.github.bearisbug.synaxis.baker.BakeTesting.REGISTRIES;
import static io.github.bearisbug.synaxis.baker.BakeTesting.request;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample rules of the far view pack ({@code docs/DESIGN-M3.md} {@code API-M3-014} 来源与采样规则, §19
 * 「远景采样规则」「粗层取每个区块里最高的细采样，前缀可单独读」) on a map of 2 × 2 chunks, 64 high, baked once.
 * Stone fills y 0 to 9; each fine sample below is a square of 4 × 4 columns whose one tall column is the
 * highest, so the sample is that column.
 */
public class FarViewSamplingTest {
    private static final SynxManifest.Bounds MAP = new SynxManifest.Bounds(0, 0, 1, 1);

    @TempDir
    static Path temp;

    private static Path build;
    private static FarViewPack.Reader pack;
    private static List<Sample> fine;
    private static List<Sample> coarse;

    /** Chunk 0, 0 has the five kinds of column and the two equal pillars; chunk 1, 0 two equal fine samples. */
    private static void generate(GenerationUnit unit) {
        unit.modifier().fillBiome(Biome.PLAINS);
        unit.modifier().fillHeight(0, 10, Block.STONE);
        final int chunkX = unit.absoluteStart().chunkX(), chunkZ = unit.absoluteStart().chunkZ();
        if (chunkX == 0 && chunkZ == 0) {
            // Sample 0, 0: a poppy on grass at y 19; only the biome cell of the grass, y 16 to 19, is forest, the
            // cell of the poppy above it plains
            for (int y = 10; y < 19; y++) unit.modifier().setBlock(1, y, 1, Block.STONE);
            unit.modifier().setBlock(1, 19, 1, Block.GRASS_BLOCK);
            unit.modifier().setBlock(1, 20, 1, Block.POPPY);
            unit.modifier().setBiome(1, 19, 1, Biome.FOREST);
            // Sample 1, 0: one layer of snow on grass at y 20
            for (int y = 10; y < 20; y++) unit.modifier().setBlock(5, y, 1, Block.STONE);
            unit.modifier().setBlock(5, 20, 1, Block.GRASS_BLOCK);
            unit.modifier().setBlock(5, 21, 1, Block.SNOW.withProperty("layers", "1"));
            // Sample 2, 0: sand at y 20, seagrass at 21, water at 22 and 23
            for (int y = 10; y < 20; y++) unit.modifier().setBlock(9, y, 1, Block.STONE);
            unit.modifier().setBlock(9, 20, 1, Block.SAND);
            unit.modifier().setBlock(9, 21, 1, Block.SEAGRASS);
            unit.modifier().setBlock(9, 22, 1, Block.WATER);
            unit.modifier().setBlock(9, 23, 1, Block.WATER);
            // Sample 3, 0: water at y 40 to 42 with air below it down to the bottom of the world
            for (int y = 0; y < 10; y++) unit.modifier().setBlock(13, y, 1, Block.AIR);
            for (int y = 40; y <= 42; y++) unit.modifier().setBlock(13, y, 1, Block.WATER);
            // Sample 0, 1: sixteen empty columns, the top of the world in a biome of its own
            for (int x = 0; x < 4; x++) {
                for (int z = 4; z < 8; z++) {
                    for (int y = 0; y < 10; y++) unit.modifier().setBlock(x, y, z, Block.AIR);
                }
            }
            unit.modifier().setBiome(0, 63, 4, Biome.SNOWY_PLAINS);
            // Sample 1, 1: three pillars up to y 30; the first by z and then x is the gold one at 6, 5
            pillar(unit, 7, 5, 30, Block.DIAMOND_BLOCK);
            pillar(unit, 6, 5, 30, Block.GOLD_BLOCK);
            pillar(unit, 5, 6, 30, Block.IRON_BLOCK);
        }
        if (chunkX == 1 && chunkZ == 0) {
            // Two fine samples of chunk 1, 0 as high: 2, 0 (lapis) comes first by z and then x, before 1, 1 (emerald)
            pillar(unit, 16 + 5, 5, 50, Block.EMERALD_BLOCK);
            pillar(unit, 16 + 9, 1, 50, Block.LAPIS_BLOCK);
        }
    }

    private static void pillar(GenerationUnit unit, int x, int z, int top, Block block) {
        for (int y = 10; y <= top; y++) unit.modifier().setBlock(x, y, z, block);
    }

    @BeforeAll
    static void bake() throws Exception {
        final Baker.Result result = Baker.bake(request("sampling", new GeneratorSource(
                new GeneratorPreset(FarViewSamplingTest::generate, 0, 64, MAP), REGISTRIES), temp, 1));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        build = result.directory();
        assertNotNull(build);
        pack = FarViewPack.Reader.read(Files.readAllBytes(build.resolve("farview").resolve("t.0.0.hfp")));
        fine = pack.samples(pack.blocks(Layer.FINE).getFirst());
        coarse = pack.samples(pack.blocks(Layer.COARSE).getFirst());
    }

    private static Sample fine(int x, int z) {
        return fine.get(z * FarViewPack.BLOCK_SIDE + x);
    }

    private static String biome(Sample sample) {
        return pack.biomes().get(sample.biome());
    }

    @Test
    public void onlyTheBlocksOfTheMapArePresent() {
        assertEquals(1, pack.blocks(Layer.FINE).size());
        assertEquals(0, pack.blocks(Layer.FINE).getFirst().bx());
        assertEquals(1, pack.blocks(Layer.COARSE).size());
        assertEquals(2, pack.header().baseLevel());
        assertEquals(0, pack.header().minY());
        assertEquals(64, pack.header().height());
    }

    /** §19 「远景采样规则」: a flower and one layer of snow are not ground, the grass under them is. */
    @Test
    public void aFlowerAndALayerOfSnowStandOnTheirGround() {
        assertEquals(new Sample(Block.GRASS_BLOCK.stateId(), 0, 19, 0, 0, fine(0, 0).biome()), fine(0, 0));
        assertEquals("minecraft:forest", biome(fine(0, 0)), "the biome of the top's cell, not of the poppy's above it");
        assertEquals(new Sample(Block.GRASS_BLOCK.stateId(), 0, 20, 0, 0, fine(1, 0).biome()), fine(1, 0));
        assertEquals("minecraft:plains", biome(fine(1, 0)));
    }

    /** Water over sand keeps the sand as ground, the seagrass in it counts as water. */
    @Test
    public void waterHasItsGroundBelowAndTheSeagrassInIt() {
        assertEquals(new Sample(Block.SAND.stateId(), Block.WATER.stateId(), 20, 21, 23, fine(2, 0).biome()), fine(2, 0));
    }

    /** Water with nothing below it to the bottom of the world has no ground. */
    @Test
    public void waterInTheAirHasNoGround() {
        assertEquals(new Sample(0, Block.WATER.stateId(), 0, 40, 42, fine(3, 0).biome()), fine(3, 0));
    }

    /** A square of empty columns is an empty sample, in the biome of the top of the world. */
    @Test
    public void anEmptyColumnHasNoHeight() {
        assertEquals(new Sample(0, 0, 0, 0, 0, fine(0, 1).biome()), fine(0, 1));
        assertEquals("minecraft:snowy_plains", biome(fine(0, 1)));
    }

    /** Of columns as high, the sample is the first by z and then x. */
    @Test
    public void ofEqualColumnsTheFirstByZThenXIsTheSample() {
        assertEquals(new Sample(Block.GOLD_BLOCK.stateId(), 0, 30, 0, 0, fine(1, 1).biome()), fine(1, 1));
    }

    /**
     * §19 「粗层取每个区块里最高的细采样」: each chunk's coarse sample is the highest of its 16 fine samples, the
     * first by z and then x of those as high, its four fields as they are.
     */
    @Test
    public void theCoarseSampleOfAChunkIsItsHighestFineSample() {
        for (int chunkX = 0; chunkX <= 1; chunkX++) {
            for (int chunkZ = 0; chunkZ <= 1; chunkZ++) {
                Sample highest = null;
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        final Sample sample = fine(chunkX * 4 + x, chunkZ * 4 + z);
                        if (highest == null || sample.top() > highest.top()) highest = sample;
                    }
                }
                assertEquals(highest, coarse.get(chunkZ * FarViewPack.BLOCK_SIDE + chunkX), chunkX + "," + chunkZ);
            }
        }
        assertEquals(fine(3, 0), coarse.get(0), "the water at y 42 is the highest of chunk 0, 0");
        assertEquals(new Sample(Block.LAPIS_BLOCK.stateId(), 0, 50, 0, 0, coarse.get(1).biome()), coarse.get(1),
                "of the two as high, the first by z and then x");
    }

    /**
     * §19: the first coarse_bytes bytes are a pack of the coarse layer alone with the same coarse samples; one
     * byte short, or with a fine_offset past its end, it is no pack.
     */
    @Test
    public void theCoarsePrefixIsAPackOfItsOwn() throws Exception {
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(build.resolve(SynxManifest.FILE_NAME)));
        final byte[] whole = Files.readAllBytes(build.resolve("farview").resolve("t.0.0.hfp"));
        final byte[] prefix = Arrays.copyOf(whole, (int) manifest.farview().getFirst().coarseBytes());
        final Path file = Files.write(temp.resolve("prefix.hfp"), prefix);

        final FarViewPack.Reader coarseOnly = FarViewPack.Reader.read(Files.readAllBytes(file));
        assertTrue(coarseOnly.coarseOnly());
        assertFalse(pack.coarseOnly());
        assertEquals(List.of(), coarseOnly.blocks(Layer.FINE));
        assertEquals(pack.biomes(), coarseOnly.biomes());
        assertEquals(coarse, coarseOnly.samples(coarseOnly.blocks(Layer.COARSE).getFirst()));

        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(Arrays.copyOf(prefix, prefix.length - 1)));
        final byte[] longer = prefix.clone();
        ByteBuffer.wrap(longer).order(ByteOrder.LITTLE_ENDIAN).putInt(32, prefix.length + 1);
        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(longer));
        assertArrayEquals(prefix, Files.readAllBytes(file));
    }
}
