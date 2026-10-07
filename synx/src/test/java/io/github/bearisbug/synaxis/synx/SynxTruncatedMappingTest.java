package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §19 「已映射的区域文件被截断之后载入转 failed」 ({@code docs/DESIGN-M3.md} {@code API-M3-005} 映射读取出错,
 * M3-S): a region file cut to nothing under a loader that mapped it. A read of the mapping faults with an
 * {@link InternalError}; the three kinds of thread that read it come out as the design says, the write
 * thread's kind as it is, written to the test output.
 */
public class SynxTruncatedMappingTest {

    @Test
    public void aRegionFileCutShortUnderTheLoaderFailsTheChunksStillToLoad(@TempDir Path dir) throws Exception {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            cutShort(env, dir);
        } finally {
            env.process().stop();
        }
    }

    private static void cutShort(Env env, Path dir) throws Exception {
        final Path build = SynxTestMap.get().copyTo(dir);
        final SynxChunkLoader loader = SynxWorlds.loader(build, SynxTestMap.REGION, SynxWorlds.WRITABLE, new MemoryOverlay());
        final Instance instance = SynxWorlds.instance(env.process(), loader);
        final SynxChunk loaded = (SynxChunk) instance.loadChunk(3, 3).join();
        assertEquals(SynxChunk.Mode.HANDLE, loaded.mode());
        final BufferedPacket segmentOne = assertInstanceOf(BufferedPacket.class, loaded.getFullDataPacket());

        try (FileChannel file = FileChannel.open(SynxTestMap.regionFile(build, 3, 3), StandardOpenOption.WRITE)) {
            file.truncate(0);
        }

        try (LogCapture logs = LogCapture.of(SynxChunkLoader.class)) {
            final SynxChunk next = (SynxChunk) instance.loadChunk(4, 3).join();
            assertEquals(SynxChunk.Mode.FAILED, next.mode(), "the load faulted, the chunk is air");
            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertEquals("Chunk 4,3 of " + SynxTestMap.get().manifest.buildId() + " does not read, it loads as read only "
                    + "air: the region file r.0.0.synx faulted on read", errors.getFirst());
        }
        assertEquals(1, loader.stats().failed());

        final IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> instance.getBlock(3 * 16 + 1, 64, 3 * 16 + 1), "a block of a handle chunk decodes from the mapping");
        assertTrue(read.getMessage().endsWith("the region file r.0.0.synx faulted on read"), read.getMessage());
        assertInstanceOf(InternalError.class, read.getCause());

        // What the write thread does with the segment ① of a handle chunk, copy it out of the mapping, takes the JVM
        // down on this machine (SIGBUS in a copy stub, not an InternalError): it runs in a JVM of its own
        final Path cut = Files.copy(SynxTestMap.regionFile(SynxTestMap.get().buildDir, 3, 3), dir.resolve("cut.synx"));
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "-XX:ErrorFile=" + dir.resolve("hs_err_%p.log"),
                CopyAfterTruncation.class.getName(), cut.toString(), String.valueOf(segmentOne.length()))
                .redirectErrorStream(true).start();
        final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        final int exit = child.waitFor();
        final String result = output.lines().filter(line -> line.startsWith("copy:") || line.contains("SIGBUS")).findFirst()
                .orElse(output.lines().findFirst().orElse("no output"));
        System.out.println("truncated mapping, the copy of segment 1 a write thread makes: exit " + exit + ", " + result);
    }

    /**
     * Maps a region file, wraps it in a {@code NetworkBuffer} as {@link SynxChunkLoader} wraps segment ①, cuts
     * the file to nothing and copies the buffer out, as {@code PlayerSocketConnection#writeSendable} does.
     */
    public static final class CopyAfterTruncation {
        private CopyAfterTruncation() {
        }

        public static void main(String[] args) throws IOException {
            final Path file = Path.of(args[0]);
            final MemorySegment mapping;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.ofShared());
            }
            final NetworkBuffer buffer = NetworkBuffer.wrap(mapping, 0, mapping.byteSize());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(0);
            }
            try {
                final byte[] bytes = new byte[Integer.parseInt(args[1])];
                buffer.copyTo(SynxFormat.RECORDS_START, bytes, 0, bytes.length);
                System.out.println("copy: " + bytes.length + " bytes");
            } catch (Throwable e) {
                System.out.println("copy: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }
}
