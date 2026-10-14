package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segment ① (REQ-M3-001, API-M3-001 「字节可复现」): the packet bytes depend on the chunk content
 * only, never on the order it was written in or on the palette form it arrived in.
 */
public class SynxChunkPacketTest {

    @Test
    public void chunkDataPacketIdIsTheEnginesPlayId() {
        assertEquals(PacketVanilla.SERVER_PACKET_PARSER.play().packetInfo(ChunkDataPacket.class).id(),
                SynxFormat.CHUNK_DATA_PACKET_ID);
    }

    /**
     * The same blocks written in a shuffled order leave the palettes in another order, and an
     * indirect palette already at its minimum width is one {@code optimize(SIZE)} leaves as it is.
     */
    @Test
    public void sameContentWrittenInAnotherOrderEncodesToTheSameBytes() {
        final ChunkDataPacket backToFront = SynxVectors.chunk(0, 0, true, false).packet();
        final ChunkDataPacket shuffled = rewritten(backToFront, new Random(7));
        assertFalse(Arrays.equals(backToFront.chunkData().data(), shuffled.chunkData().data()),
                "the two inputs must differ in their palettes for this test to mean anything");

        final SynxSettings settings = SynxVectors.settings();
        assertArrayEquals(SynxChunkPacket.encode(backToFront, settings, SynxVectors.REGISTRIES),
                SynxChunkPacket.encode(shuffled, settings, SynxVectors.REGISTRIES));
    }

    @Test
    public void directPaletteIsWrittenIndirect() {
        final ChunkDataPacket input = SynxVectors.chunk(0, 0, false, false).packet();
        assertEquals(Palette.BLOCK_PALETTE_DIRECT_BITS, SynxVectorTest.sections(input).get(2).blockStates().bitsPerEntry(),
                "section 2 of the vector input is direct");

        final byte[] framed = SynxChunkPacket.encode(input, SynxVectors.settings(), SynxVectors.REGISTRIES);
        final ChunkDataPacket decoded = SynxChunkPacket.decode(framed, SynxVectors.REGISTRIES);
        SynxVectorTest.assertSameChunk(input, decoded);
        assertEquals(0, SynxVectorTest.sections(decoded).get(15).blockStates().bitsPerEntry(), "an all air section is single valued");
    }

    /** §19 「内容哈希不随 zlib 实现变化」: another Deflater level stands in for another zlib. */
    @Test
    public void contentHashDoesNotDependOnTheCompressedBytes(@TempDir Path work) throws IOException {
        final SynxVectors.Vector vector = SynxVectors.vector("two-by-two-lit");
        final SynxRegionWriter.Encoded fast = vector.encode(SynxVectors.settings(1), work);
        final SynxRegionWriter.Encoded best = vector.encode(SynxVectors.settings(9), work);
        assertFalse(Arrays.equals(fast.payloadSha256(), best.payloadSha256()), "the levels must compress differently");
        assertArrayEquals(fast.regionContentHash(), best.regionContentHash());
    }

    @Test
    public void packetBelowTheThresholdIsFramedWithoutCompression() {
        final ChunkDataPacket packet = SynxVectors.chunk(1, 1, false, false).packet();
        final SynxSettings settings = SynxVectors.settings();
        final byte[] compressed = SynxChunkPacket.encode(packet, settings, SynxVectors.REGISTRIES);
        final byte[] body = SynxChunkPacket.body(compressed);
        assertEquals(SynxFormat.CHUNK_DATA_PACKET_ID, body[0]);

        final SynxSettings high = new SynxSettings(776, 1 << 20, -1, 0, 256, settings.registryDigest(), 1, 1, true);
        final byte[] stored = SynxChunkPacket.encode(packet, high, SynxVectors.REGISTRIES);
        final NetworkBuffer frame = NetworkBuffer.wrap(stored, 0, stored.length);
        final int frameLength = frame.read(NetworkBuffer.VAR_INT);
        assertEquals(stored.length, frame.readIndex() + frameLength, "frame length");
        assertEquals(0, frame.read(NetworkBuffer.VAR_INT), "data length 0 marks an uncompressed body");
        assertArrayEquals(body, SynxChunkPacket.body(stored));

        assertThrows(IllegalArgumentException.class,
                () -> new SynxSettings(776, 0, -1, 0, 256, settings.registryDigest(), 1, 1, true),
                "a process without compression cannot send segment ① as it is");
    }

    @Test
    public void framedBytesThatAreNotAChunkPacketAreRejected() {
        final byte[] notAChunk = {3, 0, 0x01, 0x02};
        assertThrows(IllegalArgumentException.class, () -> SynxChunkPacket.decode(notAChunk, SynxVectors.REGISTRIES));
        final byte[] truncated = Arrays.copyOf(SynxChunkPacket.encode(SynxVectors.chunk(0, 0, false, false).packet(),
                SynxVectors.settings(), SynxVectors.REGISTRIES), 40);
        assertThrows(IllegalArgumentException.class, () -> SynxChunkPacket.body(truncated));
    }

    /**
     * API-M3-001 长度上限, writing side: a body the client would not take is refused before it is framed,
     * however small it compresses. A chest without a handler sends its whole NBT in segment ①.
     */
    @Test
    public void packetOverTheUncompressedCapIsRefusedNamingTheChunk() {
        final ChunkDataPacket input = SynxVectors.chunk(3, -2, false, false).packet();
        final CompoundBinaryTag nbt = CompoundBinaryTag.builder()
                .putByteArray("filler", new byte[SynxChunkPacket.MAX_UNCOMPRESSED_BYTES]).build();
        final ChunkData data = input.chunkData();
        final ChunkDataPacket oversized = new ChunkDataPacket(input.chunkX(), input.chunkZ(),
                new ChunkData(data.heightmaps(), data.data(), Map.of(0, Block.CHEST.withNbt(nbt))), input.lightData());

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SynxChunkPacket.encode(oversized, SynxVectors.settings(), SynxVectors.REGISTRIES));
        assertTrue(refused.getMessage().startsWith("chunk 3,-2: the packet is "), refused.getMessage());
        assertTrue(refused.getMessage().endsWith(" bytes uncompressed, over 8388608"), refused.getMessage());
    }

    /** The same packet with every section palette rebuilt from a shuffled write order. */
    private static ChunkDataPacket rewritten(ChunkDataPacket packet, Random random) {
        final List<ChunkData.Section> sections = new ArrayList<>();
        for (ChunkData.Section section : SynxVectorTest.sections(packet)) {
            sections.add(new ChunkData.Section(section.blockCount(), section.liquidCount(),
                    shuffled(section.blockStates(), Palette.blocks(), random),
                    shuffled(section.biomes(), Palette.biomes(), random)));
        }
        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(SynxVectors.REGISTRIES.biome().size());
        final byte[] data = NetworkBuffer.makeArray(buffer -> {
            for (ChunkData.Section section : sections) buffer.write(type, section);
        }, SynxVectors.REGISTRIES);
        final ChunkData chunkData = packet.chunkData();
        return new ChunkDataPacket(packet.chunkX(), packet.chunkZ(),
                new ChunkData(chunkData.heightmaps(), data, chunkData.blockEntities()), packet.lightData());
    }

    private static Palette shuffled(Palette source, Palette target, Random random) {
        final int dimension = source.dimension();
        final List<int[]> positions = new ArrayList<>();
        source.getAll((x, y, z, _) -> positions.add(new int[]{x, y, z}));
        Collections.shuffle(positions, random);
        for (int[] p : positions) target.set(p[0], p[1], p[2], source.get(p[0], p[1], p[2]));
        assertTrue(target.compare(source));
        assertEquals(dimension, target.dimension());
        return target;
    }
}
