package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §19 「出站积压超限时只丢实体移动包」 ({@code ADR-M3-018}): the chunk packet of
 * a handle chunk is a {@link BufferedPacket} too, built not sheddable, and a connection over its
 * backlog keeps it while it sheds the movement queued around it. The connection is the one of
 * {@link OutboundBacklogIntegrationTest}, on a process with {@code synaxis.outbound.max-backlog-bytes}
 * at 64 KiB, and writes to a real socket; the chunk packets wrap a file mapping, as the ones of a
 * region file do.
 */
@EnvTest
public class SheddableBacklogIntegrationTest {
    private static final long BACKLOG_64_KIB = 65_536;
    private static final int MOVEMENT_BYTES = 1_228;
    private static final int LAST_MOVEMENT_BYTES = 16_384;
    private static final int CHUNK_BYTES = 1_536;
    private static final int CHUNKS = 20;

    @Test
    public void onlySheddablePacketsAreShed(@TempDir Path dir) throws Exception {
        final Env env = Env.create(builder -> builder.maxOutboundBacklogBytes(BACKLOG_64_KIB));
        try (Arena arena = Arena.ofShared();
             OutboundBacklogIntegrationTest.Harness harness = new OutboundBacklogIntegrationTest.Harness(env)) {
            final List<byte[]> chunks = queueMovementThenChunks(harness, false, dir, arena);
            // One more 16 KiB of movement: 70 KiB queued, over the 64 KiB limit
            harness.connection.sendPacket(OutboundBacklogIntegrationTest.Harness.movement(LAST_MOVEMENT_BYTES));
            assertTrue(harness.connection.isOnline(), "shedding the movement brings the backlog under the limit");
            assertEquals(0, harness.kicks());
            assertEquals(CHUNKS * (long) CHUNK_BYTES, harness.connection.backlogBytes(),
                    "what is left counts the chunk packets at their actual size");

            final byte[] expected = concat(chunks);
            harness.flushUntil(() -> harness.received.get() >= expected.length);
            // A few more flushes: had any movement survived, it would arrive now
            final int flushed = harness.flushes();
            harness.flushUntil(() -> harness.flushes() >= flushed + 3);
            assertArrayEquals(expected, harness.receivedBytes(), "the 20 chunk packets in queue order, nothing else");
            assertEquals(0, harness.connection.backlogBytes());
            assertTrue(harness.connection.isOnline());
        } finally {
            env.close();
        }
    }

    /** The counter proof: built sheddable, the chunk packets are shed with the movement. */
    @Test
    public void chunkPacketsBuiltSheddableAreShedWithTheMovement(@TempDir Path dir) throws Exception {
        final Env env = Env.create(builder -> builder.maxOutboundBacklogBytes(BACKLOG_64_KIB));
        try (Arena arena = Arena.ofShared();
             OutboundBacklogIntegrationTest.Harness harness = new OutboundBacklogIntegrationTest.Harness(env)) {
            queueMovementThenChunks(harness, true, dir, arena);
            harness.connection.sendPacket(OutboundBacklogIntegrationTest.Harness.movement(LAST_MOVEMENT_BYTES));
            assertTrue(harness.connection.isOnline());
            assertEquals(0, harness.connection.backlogBytes());
            harness.flushUntil(() -> harness.flushes() >= 3);
            assertEquals(0, harness.received.get(), "the chunk packets were shed with the movement");
        } finally {
            env.close();
        }
    }

    /**
     * 20 movement packets of 1,228 B (24 KiB), then 20 chunk packets of 1.5 KiB (30 KiB), each chunk
     * filled with its own number so that order and content show on the peer. The chunks are written
     * to one file under {@code dir}, mapped read only in {@code arena}, and each packet wraps its slice.
     */
    private static List<byte[]> queueMovementThenChunks(OutboundBacklogIntegrationTest.Harness harness,
                                                        boolean chunksSheddable, Path dir, Arena arena)
            throws IOException {
        for (int i = 0; i < 20; i++) {
            harness.connection.sendPacket(OutboundBacklogIntegrationTest.Harness.movement(MOVEMENT_BYTES));
        }
        final List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < CHUNKS; i++) {
            final byte[] bytes = new byte[CHUNK_BYTES];
            Arrays.fill(bytes, (byte) (i + 1));
            chunks.add(bytes);
        }
        final Path file = Files.write(dir.resolve("segments"), concat(chunks));
        final MemorySegment mapped;
        try (FileChannel channel = FileChannel.open(file)) {
            mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
        for (int i = 0; i < CHUNKS; i++) {
            final MemorySegment slice = mapped.asSlice((long) i * CHUNK_BYTES, CHUNK_BYTES);
            harness.connection.sendPacket(new BufferedPacket(NetworkBuffer.wrap(slice, 0, CHUNK_BYTES), 0,
                    CHUNK_BYTES, chunksSheddable));
        }
        assertEquals(20L * MOVEMENT_BYTES + CHUNKS * (long) CHUNK_BYTES, harness.connection.backlogBytes(),
                "55,280 B queued, under the limit");
        assertTrue(harness.connection.isOnline());
        return chunks;
    }

    private static byte[] concat(List<byte[]> parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
