package io.github.bearisbug.synaxis.game.protocol;

import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The height field pack of {@code docs/DESIGN-M3.md} {@code API-M3-014} through its writer and
 * reader, and the coarse sample rule {@link FarViewPack#coarsen}. The frozen bytes are
 * {@link FarViewVectorTest}'s.
 */
class FarViewPackTest {

    private static final List<String> BIOMES = List.of("minecraft:forest", "minecraft:plains", "minecraft:river");
    private static final int GRASS = 9, STONE = 1, WATER = 86, SAND = 118;

    private static FarViewPack.Writer writer() {
        return new FarViewPack.Writer(2, -1, 0, -64, 256, 32_366, 776, BIOMES);
    }

    /** A block whose sample (x, z) is ground at {@code (x * 3 + z) % 200}, biome {@code (x + z) % 3}. */
    private static List<Sample> slope() {
        final List<Sample> samples = new ArrayList<>(FarViewPack.BLOCK_SAMPLES);
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) samples.add(new Sample(GRASS, 0, (x * 3 + z) % 200, 0, 0, (x + z) % 3));
        }
        return samples;
    }

    private static List<Sample> filled(Sample sample) {
        return Collections.nCopies(FarViewPack.BLOCK_SAMPLES, sample);
    }

    @Test
    void theBlocksPerSideFollowTheLevel() {
        assertEquals(4, FarViewPack.blocksPerSide(FarViewPack.COARSE_LEVEL));
        assertEquals(16, FarViewPack.blocksPerSide(2));
        assertEquals(32, FarViewPack.blocksPerSide(1));
        assertEquals(-1, FarViewPack.tile(-1));
        assertEquals(-1, FarViewPack.tile(-256));
        assertEquals(0, FarViewPack.tile(255));
        assertEquals(1, FarViewPack.tile(256));
    }

    @Test
    void aPackReadsBackWhatTheWriterWrote() throws FarViewPack.Invalid {
        final List<Sample> water = filled(new Sample(SAND, WATER, 100, 101, 103, 2));
        final byte[] pack = writer()
                .block(Layer.FINE, 15, 0, slope())
                .block(Layer.COARSE, 3, 0, water)
                .block(Layer.FINE, 2, 7, filled(Sample.EMPTY))
                .write();

        final FarViewPack.Reader reader = FarViewPack.Reader.read(pack);
        final FarViewPack.Header header = reader.header();
        assertEquals(2, header.baseLevel());
        assertEquals(FarViewPack.COARSE_LEVEL, header.coarseLevel());
        assertEquals(-1, header.tileX());
        assertEquals(0, header.tileZ());
        assertEquals(-64, header.minY());
        assertEquals(256, header.height());
        assertEquals(32_366, header.blockStates());
        assertEquals(776, header.protocolVersion());
        assertEquals(BIOMES, reader.biomes());
        assertFalse(reader.coarseOnly());

        final List<FarViewPack.Block> coarse = reader.blocks(Layer.COARSE);
        assertEquals(1, coarse.size());
        assertEquals(3, coarse.getFirst().bx());
        assertEquals(water, reader.samples(coarse.getFirst()));
        final List<FarViewPack.Block> fine = reader.blocks(Layer.FINE);
        assertEquals(List.of("15,0", "2,7"), fine.stream().map(b -> b.bx() + "," + b.bz()).toList(),
                "fine blocks in index order: bz, then bx");
        assertEquals(slope(), reader.samples(fine.getFirst()));
        assertEquals(filled(Sample.EMPTY), reader.samples(fine.get(1)));
        reader.verify();

        assertEquals(FarViewPack.MAGIC, new String(pack, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(fine.getFirst().offset() >= header.fineOffset(), "fine blocks come after the coarse prefix");
        assertTrue(coarse.getFirst().offset() + coarse.getFirst().length() <= header.fineOffset());
    }

    @Test
    void theWriterLaysTheBlocksOutInIndexOrderWhateverTheCallOrder() {
        final byte[] one = writer().block(Layer.FINE, 3, 1, slope()).block(Layer.FINE, 1, 3, filled(Sample.EMPTY))
                .block(Layer.COARSE, 1, 0, slope()).block(Layer.COARSE, 0, 1, slope()).write();
        final byte[] other = writer().block(Layer.COARSE, 0, 1, slope()).block(Layer.FINE, 1, 3, filled(Sample.EMPTY))
                .block(Layer.COARSE, 1, 0, slope()).block(Layer.FINE, 3, 1, slope()).write();
        assertArrayEquals(one, other);
    }

    @Test
    void theCoarsePrefixIsAPackOfTheCoarseLayerAlone() throws FarViewPack.Invalid {
        final FarViewPack.Writer writer = writer().block(Layer.COARSE, 3, 0, slope()).block(Layer.FINE, 15, 0, slope());
        final byte[] pack = writer.write();
        final byte[] prefix = writer.coarsePrefix();
        final FarViewPack.Reader whole = FarViewPack.Reader.read(pack);
        assertEquals(whole.header().fineOffset(), prefix.length);
        assertArrayEquals(Arrays.copyOf(pack, prefix.length), prefix);

        final FarViewPack.Reader coarse = FarViewPack.Reader.read(prefix);
        assertTrue(coarse.coarseOnly());
        assertEquals(whole.header(), coarse.header());
        assertEquals(List.of(), coarse.blocks(Layer.FINE));
        assertEquals(whole.samples(whole.blocks(Layer.COARSE).getFirst()), coarse.samples(coarse.blocks(Layer.COARSE).getFirst()));
        coarse.verify();

        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(Arrays.copyOf(prefix, prefix.length - 1)));
        final byte[] longer = prefix.clone();
        ByteBuffer.wrap(longer).order(ByteOrder.LITTLE_ENDIAN).putInt(32, prefix.length + 1);
        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(longer));
    }

    @Test
    void aPackWithoutAnyBlockStillHasBothIndexes() throws FarViewPack.Invalid {
        final FarViewPack.Writer writer = writer();
        final byte[] pack = writer.write();
        final int biomeTable = BIOMES.stream().mapToInt(name -> 1 + name.length()).sum();
        final int coarseIndex = 16 * FarViewPack.INDEX_ENTRY_BYTES;
        assertEquals(FarViewPack.HEADER_BYTES + biomeTable + coarseIndex, writer.coarsePrefix().length);
        assertEquals(writer.coarsePrefix().length + 256 * FarViewPack.INDEX_ENTRY_BYTES, pack.length);
        final FarViewPack.Reader reader = FarViewPack.Reader.read(pack);
        assertFalse(reader.coarseOnly());
        assertEquals(List.of(), reader.blocks(Layer.COARSE));
        assertEquals(List.of(), reader.blocks(Layer.FINE));
    }

    /** Base level 1: 32 fine blocks on a side. */
    @Test
    void baseLevelOneHasAThousandFineBlocks() throws FarViewPack.Invalid {
        final byte[] pack = new FarViewPack.Writer(1, 2, -3, 0, 256, 32_366, 776, BIOMES)
                .block(Layer.FINE, 31, 31, slope()).write();
        final FarViewPack.Reader reader = FarViewPack.Reader.read(pack);
        assertEquals(1, reader.header().baseLevel());
        final FarViewPack.Block block = reader.blocks(Layer.FINE).getFirst();
        assertEquals(31, block.bx());
        assertEquals(31, block.bz());
        assertEquals(FarViewPack.HEADER_BYTES + BIOMES.stream().mapToInt(name -> 1 + name.length()).sum()
                + 16 * 8 + 1024 * 8, block.offset(), "the fine index has 32 × 32 entries");
    }

    @Test
    void theTopOfAColumnIsItsHighestPart() {
        assertEquals(-1, Sample.EMPTY.top());
        assertEquals(-1, new Sample(0, 0, 0, 0, 0, 2).top());
        assertEquals(70, new Sample(GRASS, 0, 70, 0, 0, 0).top());
        assertEquals(0, new Sample(STONE, 0, 0, 0, 0, 0).top(), "ground at index 0 is a column, not an empty one");
        assertEquals(63, new Sample(SAND, WATER, 60, 61, 63, 0).top());
        assertEquals(20, new Sample(0, WATER, 0, 10, 20, 0).top());
        assertEquals(5, new Sample(0, WATER, 0, 0, 5, 0).top());
    }

    @Test
    void aSampleKeepsTheInvariantsOfTheLayout() {
        // Ground and fluid: the fluid starts right above the ground
        assertThrows(IllegalArgumentException.class, () -> new Sample(SAND, WATER, 60, 62, 63, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(SAND, WATER, 60, 61, 60, 0));
        // Fluid alone: bottom not above top, no ground height
        assertThrows(IllegalArgumentException.class, () -> new Sample(0, WATER, 0, 21, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(0, WATER, 5, 10, 20, 0));
        // Ground alone: no fluid heights
        assertThrows(IllegalArgumentException.class, () -> new Sample(GRASS, 0, 60, 61, 61, 0));
        // Empty: no heights at all
        assertThrows(IllegalArgumentException.class, () -> new Sample(0, 0, 1, 0, 0, 0));
        // Field widths
        assertThrows(IllegalArgumentException.class, () -> new Sample(65_536, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(GRASS, 0, 256, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sample(GRASS, 0, 1, 0, 0, 256));
        assertThrows(IllegalArgumentException.class, () -> new Sample(-1, 0, 0, 0, 0, 0));
    }

    /**
     * Rule 7 of {@code API-M3-014}: the fine sample with the highest top, ties to the first by z and
     * then x, all four fields as they are.
     */
    @Test
    void coarsenTakesTheHighestFineSampleFirstByZThenX() {
        final List<Sample> fine = new ArrayList<>(Collections.nCopies(16, new Sample(GRASS, 0, 64, 0, 0, 1)));
        fine.set(1 * 4 + 0, new Sample(STONE, 0, 90, 0, 0, 0));
        fine.set(0 * 4 + 3, new Sample(SAND, WATER, 80, 81, 90, 2));
        fine.set(0 * 4 + 2, new Sample(GRASS, 0, 89, 0, 0, 1));
        assertSame(fine.get(3), FarViewPack.coarsen(2, fine), "z 0 x 3 comes before z 1 x 0");

        fine.set(0 * 4 + 1, new Sample(0, WATER, 0, 85, 90, 2));
        assertSame(fine.get(1), FarViewPack.coarsen(2, fine), "then x 1 before x 3 in the same row");

        fine.set(3 * 4 + 3, new Sample(GRASS, 0, 91, 0, 0, 0));
        assertSame(fine.get(15), FarViewPack.coarsen(2, fine), "a higher top wins wherever it is");
    }

    @Test
    void coarsenOfEmptyColumnsIsTheFirstOfThem() {
        final List<Sample> fine = new ArrayList<>(Collections.nCopies(64, new Sample(0, 0, 0, 0, 0, 2)));
        fine.set(0, new Sample(0, 0, 0, 0, 0, 1));
        assertSame(fine.getFirst(), FarViewPack.coarsen(1, fine), "base level 1: 8 × 8 fine samples a chunk");
        fine.set(63, new Sample(STONE, 0, 0, 0, 0, 0));
        assertSame(fine.get(63), FarViewPack.coarsen(1, fine), "ground at index 0 is above an empty column");
    }

    @Test
    void coarsenTakesTheFineSamplesOfOneChunk() {
        assertThrows(IllegalArgumentException.class, () -> FarViewPack.coarsen(2, Collections.nCopies(64, Sample.EMPTY)));
        assertThrows(IllegalArgumentException.class, () -> FarViewPack.coarsen(1, Collections.nCopies(16, Sample.EMPTY)));
        assertThrows(IllegalArgumentException.class, () -> FarViewPack.coarsen(3, Collections.nCopies(4, Sample.EMPTY)));
    }

    @Test
    void theWriterRefusesWhatTheLayoutCannotHold() {
        assertThrows(IllegalArgumentException.class,
                () -> new FarViewPack.Writer(3, 0, 0, 0, 256, 32_366, 776, BIOMES), "base level 3");
        assertThrows(IllegalArgumentException.class,
                () -> new FarViewPack.Writer(2, 0, 0, 0, 257, 32_366, 776, BIOMES), "a height a y index cannot cover");
        assertThrows(IllegalArgumentException.class,
                () -> new FarViewPack.Writer(2, 0, 0, 0, 256, 32_366, 776, List.of()), "an empty biome table");
        assertThrows(IllegalArgumentException.class, () -> new FarViewPack.Writer(2, 0, 0, 0, 256, 32_366, 776,
                List.of("minecraft:plains", "minecraft:forest")), "a biome table out of order");
        assertThrows(IllegalArgumentException.class, () -> new FarViewPack.Writer(2, 0, 0, 0, 256, 32_366, 776,
                List.of("minecraft:plains", "minecraft:plains")), "a name twice");
        final List<String> many = new ArrayList<>();
        for (int i = 0; i < 256; i++) many.add("minecraft:b%03d".formatted(i));
        assertThrows(IllegalArgumentException.class, () -> new FarViewPack.Writer(2, 0, 0, 0, 256, 32_366, 776, many));

        assertThrows(IllegalArgumentException.class, () -> writer().block(Layer.FINE, 16, 0, slope()), "past the tile");
        assertThrows(IllegalArgumentException.class, () -> writer().block(Layer.COARSE, 4, 0, slope()), "past the tile");
        assertThrows(IllegalArgumentException.class, () -> writer().block(Layer.FINE, 0, 0, slope().subList(0, 4095)));
        assertThrows(IllegalArgumentException.class,
                () -> writer().block(Layer.FINE, 0, 0, filled(new Sample(GRASS, 0, 1, 0, 0, 3))), "biome past the table");
        assertThrows(IllegalArgumentException.class,
                () -> writer().block(Layer.FINE, 0, 0, filled(new Sample(32_366, 0, 1, 0, 0, 0))), "state past the registry");
        assertThrows(IllegalArgumentException.class,
                () -> new FarViewPack.Writer(2, 0, 0, 0, 128, 32_366, 776, BIOMES).block(Layer.FINE, 0, 0,
                        filled(new Sample(GRASS, 0, 128, 0, 0, 0))), "a y index past the world");
        assertThrows(IllegalStateException.class,
                () -> writer().block(Layer.FINE, 0, 0, slope()).block(Layer.FINE, 0, 0, slope()), "a block twice");
    }

    @Test
    void theReaderRefusesAPackThatIsNotOne() {
        final byte[] pack = writer().block(Layer.COARSE, 0, 0, slope()).block(Layer.FINE, 0, 0, slope()).write();
        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(Arrays.copyOf(pack, 63)));
        final byte[] magic = pack.clone();
        magic[0] = 'X';
        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(magic));
        final byte[] noBiome = pack.clone();
        noBiome[9] = 0;
        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(noBiome));
        final byte[] unsorted = pack.clone();
        unsorted[FarViewPack.HEADER_BYTES + 1 + "minecraft:".length()] = 'z';
        assertThrows(FarViewPack.Invalid.class, () -> FarViewPack.Reader.read(unsorted), "minecraft:zorest after plains");
    }
}
