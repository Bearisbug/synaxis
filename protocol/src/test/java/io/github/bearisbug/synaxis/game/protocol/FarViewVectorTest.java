package io.github.bearisbug.synaxis.game.protocol;

import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The frozen far view vectors of {@code docs/DESIGN-M3.md} §24 against {@link FarViewPack} and
 * {@link FarViewAnnouncement}; the client mod runs the same directory. {@code manifest.txt} freezes
 * both {@code format_version}s and the SHA-256 of every file, so changing a layout without raising
 * its version and refreezing is a red build. The written pack bytes are only compared where
 * {@link java.util.zip.Deflater} gives the zlib fingerprint frozen beside them; everywhere else the
 * samples are.
 */
class FarViewVectorTest {

    private static Path dir() {
        return FarViewVectors.dir();
    }

    private static byte[] read(String file) throws IOException {
        return Files.readAllBytes(dir().resolve(file));
    }

    @Test
    void theManifestFreezesBothVersionsAndEveryFile() throws IOException {
        final List<String> lines = Files.readAllLines(dir().resolve("manifest.txt"));
        assertEquals("pack_format_version " + FarViewPack.FORMAT_VERSION, lines.get(0),
                "a pack layout change must raise FarViewPack.FORMAT_VERSION and refreeze the vectors in the same change");
        assertEquals("announcement_format_version " + FarViewAnnouncement.FORMAT_VERSION, lines.get(1),
                "an announcement layout change must raise FarViewAnnouncement.FORMAT_VERSION and refreeze the vectors");
        assertTrue(lines.get(2).startsWith("zlib "), lines.get(2));
        final TreeSet<String> listed = new TreeSet<>();
        for (String line : lines.subList(3, lines.size())) {
            final String[] parts = line.split(" {2}", 2);
            assertEquals(parts[0], FarViewVectorGenerator.hex(read(parts[1])), parts[1] + " changed after it was frozen");
            listed.add(parts[1]);
        }
        final TreeSet<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.walk(dir())) {
            files.filter(Files::isRegularFile)
                    .map(path -> dir().relativize(path).toString().replace('\\', '/'))
                    .filter(name -> !name.equals("manifest.txt") && !name.equals("README.md"))
                    .forEach(present::add);
        }
        assertEquals(present, listed, "every vector file is listed in manifest.txt and nothing else");
        assertEquals(21, listed.size(), "six regular vectors and fifteen edge vectors");
    }

    static List<String> announcements() {
        return List.copyOf(FarViewVectors.announcements().keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("announcements")
    void anAnnouncementDecodesAndReEncodesByteForByte(String file) throws IOException {
        final byte[] frozen = read(file);
        final FarViewAnnouncement expected = FarViewVectors.announcements().get(file);
        final MsgBuf reader = MsgBuf.reading(frozen);
        assertEquals(expected, FarViewAnnouncement.read(reader), file);
        assertEquals(0, reader.remaining(), file + " has bytes the decoder never reads");
        assertArrayEquals(frozen, FarViewVectors.encode(expected), file);
    }

    @Test
    void theNineTileAnnouncementIsOneWholeTileAndEightCoarse() throws IOException {
        final FarViewAnnouncement nine = FarViewAnnouncement.read(MsgBuf.reading(read(FarViewVectors.NINE_TILES)));
        assertEquals(9, nine.tiles().size());
        assertEquals(1, nine.tiles().stream().filter(t -> t.layer() == FarViewAnnouncement.LAYER_FULL).count());
        final FarViewAnnouncement.Tile center = nine.tiles().get(4);
        assertEquals(0, center.tileX());
        assertEquals(0, center.tileZ());
        assertEquals(FarViewAnnouncement.LAYER_FULL, center.layer());
    }

    /** {@code pack-small.samples.txt} parsed: the samples by layer and sample coordinate in the tile. */
    private static Map<String, Sample> expectedSamples(List<String> lines) {
        final Map<String, Sample> samples = new TreeMap<>();
        for (String line : lines) {
            if (!line.startsWith("coarse ") && !line.startsWith("fine ")) continue;
            final String[] f = line.split(" ", -1);
            samples.put(f[0] + " " + f[1] + " " + f[2], new Sample(Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                    Integer.parseInt(f[5]), Integer.parseInt(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8])));
        }
        return samples;
    }

    /** Every sample of the present blocks of {@code reader} by layer and sample coordinate, the empty ones of biome 0 left out. */
    private static Map<String, Sample> samples(FarViewPack.Reader reader) throws FarViewPack.Invalid {
        final Map<String, Sample> samples = new TreeMap<>();
        for (Layer layer : Layer.values()) {
            for (FarViewPack.Block block : reader.blocks(layer)) {
                final List<Sample> content = reader.samples(block);
                for (int i = 0; i < content.size(); i++) {
                    if (content.get(i).equals(Sample.EMPTY)) continue;
                    final int x = block.bx() * 64 + i % 64, z = block.bz() * 64 + i / 64;
                    samples.put(layer.name().toLowerCase(Locale.ROOT) + " " + x + " " + z, content.get(i));
                }
            }
        }
        return samples;
    }

    @Test
    void theSmallPackHoldsTheSamplesWrittenDownBesideIt() throws IOException, FarViewPack.Invalid {
        final FarViewPack.Reader reader = FarViewPack.Reader.read(read(FarViewVectors.PACK));
        reader.verify();
        final List<String> text = Files.readAllLines(dir().resolve(FarViewVectors.SAMPLES), StandardCharsets.UTF_8);
        assertEquals(FarViewVectors.samplesText(reader.header()), String.join("\n", text) + "\n",
                "pack-small.samples.txt is what FarViewVectors says");
        assertEquals(FarViewVectors.BIOMES, reader.biomes());
        assertEquals(new FarViewPack.Header(2, 4, -1, 0, -64, 256, FarViewVectors.BLOCK_STATES, 776,
                reader.header().fineOffset()), reader.header());
        assertEquals(List.of("COARSE 3 0"), reader.blocks(Layer.COARSE).stream()
                .map(b -> b.layer() + " " + b.bx() + " " + b.bz()).toList());
        assertEquals(List.of("FINE 15 0"), reader.blocks(Layer.FINE).stream()
                .map(b -> b.layer() + " " + b.bx() + " " + b.bz()).toList());
        final Map<String, Sample> expected = expectedSamples(text);
        assertEquals(4 + 64, expected.size());
        final Map<String, Sample> present = samples(reader);
        expected.forEach((at, sample) -> {
            if (!sample.equals(Sample.EMPTY)) assertEquals(sample, present.get(at), at);
        });
        assertEquals(expected.values().stream().filter(s -> !s.equals(Sample.EMPTY)).count(), present.size(),
                "every other sample is an empty column of biome 0");

        // The five kinds of column, the tie and the hidden area are in it
        assertEquals(new Sample(0, FarViewVectors.WATER, 0, 140, 142, FarViewVectors.RIVER), expected.get("fine 1016 0"));
        assertEquals(new Sample(0, FarViewVectors.WATER, 0, 0, 10, FarViewVectors.RIVER), expected.get("fine 1017 0"));
        assertEquals(new Sample(0, 0, 0, 0, 0, FarViewVectors.RIVER), expected.get("fine 1018 0"));
        assertEquals(new Sample(FarViewVectors.SAND, FarViewVectors.WATER, 120, 121, 124, FarViewVectors.RIVER),
                expected.get("fine 1019 3"));
        assertEquals(new Sample(FarViewVectors.GRASS, 0, 150, 0, 0, FarViewVectors.FOREST), expected.get("coarse 255 0"),
                "the tie of chunk -1,0 goes to z 0, x 6");
        assertEquals(new Sample(FarViewVectors.STONE, 0, 129, 0, 0, FarViewVectors.PLAINS), expected.get("fine 1021 5"),
                "a hidden sample is stone at the top of the pond on its rim");
        assertEquals(new Sample(FarViewVectors.STONE, 0, 0, 0, 0, FarViewVectors.PLAINS), expected.get("coarse 255 1"));
        assertFalse(expected.containsValue(new Sample(FarViewVectors.RED_CONCRETE, 0, 200, 0, 0, FarViewVectors.FOREST)),
                "the tower in the hidden area leaks through neither layer");
    }

    @Test
    void theCoarsePrefixIsTheSmallPackCutAtFineOffset() throws IOException, FarViewPack.Invalid {
        final byte[] pack = read(FarViewVectors.PACK);
        final byte[] prefix = read(FarViewVectors.COARSE_PACK);
        final FarViewPack.Reader whole = FarViewPack.Reader.read(pack);
        assertEquals(whole.header().fineOffset(), prefix.length);
        assertArrayEquals(Arrays.copyOf(pack, prefix.length), prefix);
        final FarViewPack.Reader coarse = FarViewPack.Reader.read(prefix);
        coarse.verify();
        assertTrue(coarse.coarseOnly());
        final Map<String, Sample> wholeSamples = samples(whole);
        wholeSamples.keySet().removeIf(at -> at.startsWith("fine "));
        assertEquals(wholeSamples, samples(coarse));
    }

    @Test
    void theWriterReproducesThePackBytesOnTheFrozenZlib() throws IOException {
        final String frozen = Files.readAllLines(dir().resolve("manifest.txt")).get(2);
        assumeTrue(frozen.equals("zlib " + FarViewVectors.zlibFingerprint()),
                "this JDK's zlib differs from the one the far view vectors were frozen with; the samples are compared instead");
        final FarViewPack.Writer writer = FarViewVectors.packSmall();
        assertArrayEquals(read(FarViewVectors.PACK), writer.write());
        assertArrayEquals(read(FarViewVectors.COARSE_PACK), writer.coarsePrefix());
    }

    /** Each edge pack, and the words its refusal must name so that it fails for the reason it was made for. */
    static List<String[]> edgePacks() {
        return List.of(
                new String[]{"pack-format-version-2.hfp", "format_version 2"},
                new String[]{"pack-flags.hfp", "flags"},
                new String[]{"pack-base-level-3.hfp", "base_level 3"},
                new String[]{"pack-coarse-level-3.hfp", "coarse_level 3"},
                new String[]{"pack-fine-offset-past-end.hfp", "fine_offset"},
                new String[]{"pack-coarse-block-past-fine-offset.hfp", "coarse block 3,0"},
                new String[]{"pack-index-past-end.hfp", "past the end"},
                new String[]{"pack-overlapping-blocks.hfp", "overlaps"},
                new String[]{"pack-block-not-32768.hfp", "32768"},
                new String[]{"pack-invariant.hfp", "invariant"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edgePacks")
    void anEdgePackIsRefused(String file, String reason) throws IOException {
        final byte[] bytes = read("edge/" + file);
        final FarViewPack.Invalid thrown = assertThrows(FarViewPack.Invalid.class,
                () -> FarViewPack.Reader.read(bytes).verify(), file);
        assertTrue(thrown.getMessage().contains(reason), file + ": " + thrown.getMessage());
    }

    static List<String[]> edgeAnnouncements() {
        return List.of(
                new String[]{"announce-count-65.bin", "count 65"},
                new String[]{"announce-url-too-long.bin", "string length 1025"},
                new String[]{"announce-duplicate-tile.bin", "0,0"},
                new String[]{"announce-layer-2.bin", "layer 2"},
                new String[]{"announce-no-full-tile.bin", "layer 0"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edgeAnnouncements")
    void anEdgeAnnouncementIsMalformed(String file, String reason) throws IOException {
        final byte[] bytes = read("edge/" + file);
        final MalformedMessageException thrown = assertThrows(MalformedMessageException.class,
                () -> FarViewAnnouncement.read(MsgBuf.reading(bytes)), file);
        assertTrue(thrown.getMessage().contains(reason), file + ": " + thrown.getMessage());
    }

    @Test
    void everyEdgeFileHasItsCase() throws IOException {
        final List<String> cases = new ArrayList<>();
        for (String[] edge : edgePacks()) cases.add(edge[0]);
        for (String[] edge : edgeAnnouncements()) cases.add(edge[0]);
        try (Stream<Path> files = Files.list(dir().resolve("edge"))) {
            assertEquals(new TreeSet<>(cases), new TreeSet<>(files.map(p -> p.getFileName().toString()).toList()));
        }
    }
}
