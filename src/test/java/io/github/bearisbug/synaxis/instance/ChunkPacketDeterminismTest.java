package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The same generator input encodes to the same uncompressed chunk packet bytes in every JVM
 * (REQ-M3-003). Each run is a separate process because per-JVM state (hash seeds, identity hash
 * codes) is exactly what a single-process comparison cannot see.
 */
public class ChunkPacketDeterminismTest {

    private static final int GRID = 3;
    private static final int RUNS = 6;
    private static final String[] FIELDS = {"head", "heightmaps", "sections", "blockEntities", "light"};

    @Test
    public void samePacketBytesAcrossJvms(@TempDir Path dir) throws Exception {
        final List<String> first = run(dir.resolve("run0.txt"));
        for (int i = 1; i < RUNS; i++) {
            final List<String> other = run(dir.resolve("run" + i + ".txt"));
            assertEquals(first.size(), other.size(), "chunk count");
            for (int line = 0; line < first.size(); line++) {
                final String difference = firstDifference(first.get(line), other.get(line));
                if (difference != null) fail("run 0 and run " + i + " differ: " + difference);
            }
        }
    }

    private static List<String> run(Path output) throws IOException, InterruptedException {
        final Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        final Process process = new ProcessBuilder(java.toString(), "-Xmx512m",
                "-cp", System.getProperty("java.class.path"),
                ChunkPacketDeterminismTest.class.getName(), output.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.resolveSibling(output.getFileName() + ".log").toFile())
                .start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child JVM timed out");
        assertEquals(0, process.exitValue(), () -> "child JVM failed: " + readLog(output));
        return Files.readAllLines(output);
    }

    private static String readLog(Path output) {
        try {
            return Files.readString(output.resolveSibling(output.getFileName() + ".log"));
        } catch (IOException e) {
            return e.toString();
        }
    }

    /** Line format: {@code kind cx cz end0,end1,... hex}, the ends being the field boundaries. */
    private static String firstDifference(String a, String b) {
        if (a.equals(b)) return null;
        final String[] pa = a.split(" ", -1);
        final String[] pb = b.split(" ", -1);
        final String chunk = pa[0] + " chunk " + pa[1] + "," + pa[2];
        final byte[] bytesA = HexFormat.of().parseHex(pa[4]);
        final byte[] bytesB = HexFormat.of().parseHex(pb[4]);
        if (bytesA.length != bytesB.length) {
            return chunk + ": length " + bytesA.length + " vs " + bytesB.length;
        }
        int offset = 0;
        while (bytesA[offset] == bytesB[offset]) offset++;
        final String[] ends = pa[3].split(",", -1);
        int start = 0;
        for (int field = 0; field < ends.length; field++) {
            final int end = Integer.parseInt(ends[field]);
            if (offset < end) {
                return chunk + ": first differing byte at packet offset " + offset + " (" + FIELDS[field]
                        + " offset " + (offset - start) + ", " + (end - start) + " bytes), fields "
                        + pa[3] + " vs " + pb[3];
            }
            start = end;
        }
        throw new AssertionError("offset past the last field");
    }

    /** Child JVM: generates the same small world twice (no light, lazy light) and writes every packet. */
    public static void main(String[] args) throws IOException {
        final ServerProcess process = ServerProcess.of();
        final Block sign = Block.OAK_SIGN.withNbt(readBack(CompoundBinaryTag.builder()
                .put("front_text", signText("\"Hello\"", "black"))
                .put("back_text", signText("\"Synaxis\"", "blue"))
                .putBoolean("is_waxed", false)
                .build()));
        final List<String> lines = new ArrayList<>();
        for (boolean lighting : new boolean[]{false, true}) {
            final InstanceContainer instance = process.instance().createInstanceContainer();
            if (lighting) instance.setChunkSupplier(LightingChunk::new);
            instance.setGenerator(unit -> {
                final int baseX = unit.absoluteStart().blockX();
                final int baseZ = unit.absoluteStart().blockZ();
                unit.modifier().fillHeight(-64, 0, Block.DEEPSLATE);
                unit.modifier().fillHeight(0, 40, Block.STONE);
                for (int i = 0; i < 16; i++) {
                    unit.modifier().setBlock(baseX + i, 40, baseZ + (i * 7 & 15), Block.DIRT);
                    unit.modifier().setBlock(baseX + (i * 5 & 15), 40, baseZ + i, Block.WATER);
                }
                // Several block entities per chunk
                for (int i = 0; i < 4; i++) {
                    unit.modifier().setBlock(baseX + 3 * i + 1, 41, baseZ + 2 * i + 3, Block.CHEST);
                }
                unit.modifier().setBlock(baseX + 14, 41, baseZ + 14, sign);
            });
            final List<Chunk> chunks = new ArrayList<>();
            for (int z = 0; z < GRID; z++) {
                for (int x = 0; x < GRID; x++) chunks.add(instance.loadChunk(x, z).join());
            }
            for (Chunk chunk : chunks) {
                for (Section section : chunk.getSections()) section.blockPalette().optimize(Palette.Optimization.SIZE);
            }
            for (Chunk chunk : chunks) {
                final ChunkDataPacket packet = (ChunkDataPacket) ((CachedPacket) chunk.getFullDataPacket()).packet();
                lines.add((lighting ? "lit" : "plain") + " " + chunk.getChunkX() + " " + chunk.getChunkZ()
                        + " " + fieldEnds(packet, process) + " "
                        + HexFormat.of().formatHex(NetworkBuffer.makeArray(ChunkDataPacket.SERIALIZER, packet, process)));
            }
        }
        Files.write(Path.of(args[0]), lines);
        System.exit(0);
    }

    private static CompoundBinaryTag signText(String firstLine, String color) {
        final StringBinaryTag empty = StringBinaryTag.stringBinaryTag("\"\"");
        return CompoundBinaryTag.builder()
                .put("messages", ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,
                        List.of(StringBinaryTag.stringBinaryTag(firstLine), empty, empty, empty)))
                .putString("color", color)
                .putBoolean("has_glowing_text", false)
                .build();
    }

    /**
     * {@code tag} written and read back through adventure's {@link BinaryTagIO}, the way the Anvil
     * source and segment ③ read block entities: every compound it reads iterates its keys in an
     * order that changes from one JVM to the next.
     */
    private static CompoundBinaryTag readBack(CompoundBinaryTag tag) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, output);
        return BinaryTagIO.reader().read(new ByteArrayInputStream(output.toByteArray()));
    }

    private static String fieldEnds(ChunkDataPacket packet, ServerProcess process) {
        final ChunkData data = packet.chunkData();
        final int head = 8;
        final int heightmaps = head + NetworkBuffer.makeArray(Heightmap.Type.NETWORK_TYPE
                .mapValue(NetworkBuffer.LONG_ARRAY), data.heightmaps()).length;
        final int sections = heightmaps + NetworkBuffer.makeArray(NetworkBuffer.BYTE_ARRAY, data.data()).length;
        final int chunkData = head + NetworkBuffer.makeArray(ChunkData.NETWORK_TYPE, data, process).length;
        final int light = chunkData + NetworkBuffer.makeArray(LightData.NETWORK_TYPE, packet.lightData(), process).length;
        return head + "," + heightmaps + "," + sections + "," + chunkData + "," + light;
    }
}
