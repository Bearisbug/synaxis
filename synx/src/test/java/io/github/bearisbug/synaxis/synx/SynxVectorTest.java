package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The frozen vectors of {@code docs/DESIGN-M3.md} §24 against the reader and the writer
 * (REQ-M3-001, §19 「区域文件读写与测试向量一致」).
 * <p>
 * The reader and the content hashes are held to the vectors on every JDK. The written bytes are
 * only held to them where {@link java.util.zip.Deflater} produces the output it produced when the
 * vectors were frozen, which {@code manifest.txt} records as a fingerprint: zlib output may differ
 * between JDK builds, the uncompressed content may not.
 */
public class SynxVectorTest {

    @Test
    public void manifestFreezesEveryVectorFile() throws IOException {
        final List<String> lines = Files.readAllLines(SynxVectors.DIR.resolve("manifest.txt"));
        assertEquals("format_version " + SynxFormat.FORMAT_VERSION, lines.getFirst(),
                "a layout change must bump format_version and refreeze the vectors in the same change");
        final TreeSet<String> listed = new TreeSet<>();
        for (String line : lines.subList(2, lines.size())) {
            final String[] parts = line.split(" {2}", 2);
            final byte[] bytes = Files.readAllBytes(SynxVectors.DIR.resolve(parts[1]));
            assertEquals(parts[0], SynxDigests.hex(SynxDigests.sha256(bytes)), parts[1] + " changed after it was frozen");
            listed.add(parts[1]);
        }
        final TreeSet<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SynxVectors.DIR)) {
            files.filter(Files::isRegularFile)
                    .map(path -> SynxVectors.DIR.relativize(path).toString().replace('\\', '/'))
                    .filter(name -> !name.equals("manifest.txt") && !name.equals("README.md"))
                    .forEach(present::add);
        }
        assertEquals(present, listed, "every vector file is listed in manifest.txt and nothing else");
        assertEquals(13, listed.size(), "four regular vectors, the manifest and eight edge vectors");
    }

    @Test
    public void readerDecodesEveryRegularVector() throws Exception {
        final SynxSettings settings = SynxVectors.settings();
        for (SynxVectors.Vector vector : SynxVectors.vectors()) {
            try (Arena arena = Arena.ofConfined()) {
                final SynxRegion region = SynxRegion.open(vector.file(), arena);
                final SynxHeader header = region.header();
                assertEquals(SynxHeader.of(settings, vector.regionX(), vector.regionZ(), header.contentHash(),
                        header.regionContentHash(), header.payloadSha256()), header, vector.name());
                region.checkAgainst(SynxVectors.expectations());
                region.verifyPayload();
                assertEquals(vector.chunks().size(), region.chunkCount(), vector.name());
                assertArrayEquals(header.regionContentHash(), region.computeRegionContentHash(), vector.name());

                final Map<Integer, SynxVectors.ChunkInput> inputs = new HashMap<>();
                for (SynxVectors.ChunkInput input : vector.chunks()) {
                    inputs.put(SynxFormat.index(input.packet().chunkX(), input.packet().chunkZ()), input);
                }
                for (int i = 0; i < SynxFormat.INDEX_ENTRIES; i++) {
                    final int chunkX = vector.regionX() * 32 + (i & 31);
                    final int chunkZ = vector.regionZ() * 32 + (i >> 5);
                    final SynxVectors.ChunkInput input = inputs.get(i);
                    final SynxSlot slot = region.slot(chunkX, chunkZ);
                    if (input == null) {
                        assertInstanceOf(SynxSlot.Absent.class, slot, vector.name() + " " + chunkX + "," + chunkZ);
                        continue;
                    }
                    final SynxSlot.Present present = assertInstanceOf(SynxSlot.Present.class, slot, vector.name());
                    assertEquals(SynxFormat.NAVIGATION_BYTES, present.navigation().byteSize());
                    final SynxChunkRecord record = present.record();
                    final SynxChunkRecord expected = input.toRecord(settings);
                    assertArrayEquals(SynxChunkPacket.body(expected.packet()), SynxChunkPacket.body(record.packet()),
                            vector.name() + ": segment ① content");
                    assertArrayEquals(expected.navigation(), record.navigation());
                    assertArrayEquals(expected.columns(), record.columns());
                    assertEquals(sorted(expected.blockEntities()), record.blockEntities());
                    assertEquals(expected.staticEntities(), record.staticEntities());
                    assertSameChunk(input.packet(), SynxChunkPacket.decode(record.packet(), SynxVectors.REGISTRIES));
                }
            }
        }
    }

    @Test
    public void writerReproducesTheContentHashesOnAnyJdk() throws Exception {
        final List<SynxRegionWriter.Encoded> encoded = new ArrayList<>();
        for (SynxVectors.Vector vector : SynxVectors.vectors()) encoded.add(vector.encode(SynxVectors.settings()));
        final byte[] contentHash = SynxVectors.contentHash(encoded);
        for (int i = 0; i < encoded.size(); i++) {
            final SynxVectors.Vector vector = SynxVectors.vectors().get(i);
            try (Arena arena = Arena.ofConfined()) {
                final SynxHeader header = SynxRegion.open(vector.file(), arena).header();
                assertArrayEquals(header.regionContentHash(), encoded.get(i).regionContentHash(), vector.name());
                assertArrayEquals(header.contentHash(), contentHash, vector.name());
            }
        }
    }

    @Test
    public void writerReproducesTheVectorBytesOnTheFrozenZlib() throws IOException {
        final String frozen = Files.readAllLines(SynxVectors.DIR.resolve("manifest.txt")).get(1);
        assumeTrue(frozen.equals("zlib " + SynxVectors.zlibFingerprint()),
                "this JDK's zlib differs from the one the vectors were frozen with");
        for (Map.Entry<String, byte[]> file : SynxVectorGenerator.files().entrySet()) {
            assertArrayEquals(Files.readAllBytes(SynxVectors.DIR.resolve(file.getKey())), file.getValue(), file.getKey());
        }
    }

    private static List<SynxBlockEntity> sorted(List<SynxBlockEntity> entities) {
        final List<SynxBlockEntity> sorted = new ArrayList<>(entities);
        sorted.sort((a, b) -> a.sectionY() != b.sectionY()
                ? Integer.compare(a.sectionY(), b.sectionY()) : Integer.compare(a.index(), b.index()));
        return sorted;
    }

    /** Same blocks, biomes, heightmaps and light, and every block palette single valued or indirect. */
    static void assertSameChunk(ChunkDataPacket expected, ChunkDataPacket actual) {
        assertEquals(expected.chunkX(), actual.chunkX());
        assertEquals(expected.chunkZ(), actual.chunkZ());
        assertArrayEquals(NetworkBuffer.makeArray(LightData.NETWORK_TYPE, expected.lightData()),
                NetworkBuffer.makeArray(LightData.NETWORK_TYPE, actual.lightData()), "light");
        assertEquals(expected.chunkData().heightmaps().keySet(), actual.chunkData().heightmaps().keySet());
        expected.chunkData().heightmaps().forEach((type, data) ->
                assertArrayEquals(data, actual.chunkData().heightmaps().get(type), type.name()));
        final List<ChunkData.Section> want = sections(expected);
        final List<ChunkData.Section> got = sections(actual);
        assertEquals(want.size(), got.size());
        for (int i = 0; i < want.size(); i++) {
            assertEquals(want.get(i).blockCount(), got.get(i).blockCount(), "section " + i);
            assertEquals(want.get(i).liquidCount(), got.get(i).liquidCount(), "section " + i);
            assertTrue(want.get(i).blockStates().compare(got.get(i).blockStates()), "blocks of section " + i);
            assertTrue(want.get(i).biomes().compare(got.get(i).biomes()), "biomes of section " + i);
            final int bits = got.get(i).blockStates().bitsPerEntry();
            assertTrue(bits == 0 || (bits >= Palette.BLOCK_PALETTE_MIN_BITS && bits <= Palette.BLOCK_PALETTE_MAX_BITS),
                    "section " + i + " has a direct block palette of " + bits + " bits");
        }
    }

    static List<ChunkData.Section> sections(ChunkDataPacket packet) {
        final NetworkBuffer.Type<ChunkData.Section> type =
                ChunkData.Section.networkType(SynxVectors.REGISTRIES.biome().size());
        final byte[] data = packet.chunkData().data();
        final NetworkBuffer buffer = NetworkBuffer.wrap(data, 0, data.length, SynxVectors.REGISTRIES);
        final List<ChunkData.Section> sections = new ArrayList<>();
        while (buffer.readableBytes() > 0) sections.add(buffer.read(type));
        return sections;
    }
}
