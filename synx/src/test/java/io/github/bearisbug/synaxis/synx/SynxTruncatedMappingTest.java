package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
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
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §19 「已映射的区域文件被截断之后载入转 failed」 ({@code docs/DESIGN-M3.md} {@code API-M3-005} 映射读取出错,
 * M3-S): a region file cut to nothing under a loader that mapped it. A read of the mapping faults with an
 * {@link InternalError}; the kinds of thread that read it come out as the design says, the write
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
     * §19 「已映射的区域文件被截断之后运行时光照照常」: the runtime light reads segment ① of handle chunks
     * from the mapping as well. Cut short, the install of a handle target fails as that one target, and a
     * recomputation that takes the light of a handle target fails as that one task; nothing else stops.
     */
    @Test
    public void aRegionFileCutShortUnderTheRuntimeLightFailsOneInstallAndOneRecomputation(@TempDir Path dir)
            throws Exception {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            cutShortUnderTheLight(env, dir);
        } finally {
            env.process().stop();
        }
    }

    private static void cutShortUnderTheLight(Env env, Path dir) throws Exception {
        final Path build = SynxTestMap.get().copyTo(dir);
        final SynxChunkLoader loader = SynxWorlds.loader(build, SynxTestMap.REGION, SynxWorlds.WRITABLE,
                new MemoryOverlay());
        final InstanceContainer instance = env.process().instance().createInstanceContainer(
                SynxWorlds.dimension(env.process()), loader);
        final RelightWorld.ManualExecutor executor = new RelightWorld.ManualExecutor();
        final SynxRelight relight = loader.startRelight(instance, executor);
        for (int chunkX = 8; chunkX <= 12; chunkX++) {
            for (int chunkZ = 8; chunkZ <= 12; chunkZ++) instance.loadChunk(chunkX, chunkZ).join();
        }
        // A torch on the east edge of 10,10, far above the terrain: the block light of 11,10 changes
        final int x = 10 * 16 + 15, y = SynxTestMap.FEATURE_Y + 10, z = 10 * 16 + 8;
        instance.setBlock(x, y, z, Block.TORCH);
        assertTrue(executor.runNext(), "the snapshot of 10,10");

        // The computation cannot have the write-lock of 11,10 within its 50 ms: 11,10 stays a handle chunk
        final SynxChunk east = (SynxChunk) instance.getChunk(11, 10);
        final CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        final Thread holder = Thread.ofPlatform().start(() -> {
            east.lockWriteLock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                east.unlockWriteLock();
            }
        });
        locked.await();
        try {
            assertTrue(executor.runNext(), "the computation of 10,10");
        } finally {
            release.countDown();
            holder.join();
        }
        assertEquals(SynxChunk.Mode.HANDLE, east.mode(), "11,10 was not materialized for its light");

        try (FileChannel file = FileChannel.open(SynxTestMap.regionFile(build, 10, 10), StandardOpenOption.WRITE)) {
            file.truncate(0);
        }

        // The install of 11,10 decodes its light from the mapping
        try (LogCapture logs = LogCapture.of(SynxRelight.class)) {
            for (int tick = 0; tick < 100 && relight.pendingCenters() > 0; tick++) relight.installPhase();
            assertEquals(List.of("The light install of 11,10 failed, it keeps its old light"),
                    logs.messagesAt(Level.ERROR));
        }
        assertEquals(0, relight.pendingCenters(), "the other eight targets are handled, the task is done");
        assertEquals(SynxChunk.Mode.HANDLE, east.mode(), "11,10 keeps its old light");
        assertEquals(0, relight.stats().skipped(), "a failed install is no skipped target");

        // The recomputation takes the light of the handle targets 9,9 to 9,11 and 11,10 from the mapping
        try (LogCapture logs = LogCapture.of(SynxRelight.class)) {
            instance.setBlock(x, y, z, Block.AIR);
            executor.runAll();
            assertEquals(List.of("The light recomputation of center 10,10 failed, its 3×3 keeps its old light"),
                    logs.messagesAt(Level.ERROR));
        }
        assertEquals(0, relight.pendingCenters(), "the center has left");
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
