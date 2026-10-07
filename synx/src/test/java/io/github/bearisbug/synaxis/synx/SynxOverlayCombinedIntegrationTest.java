package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §19 {@code REQ-M3-011}: the six scenarios marked 「A、C 合并后」, on the
 * real {@link OverlayStore} behind real {@link SynxChunk}s of the {@link SynxTestMap} (§28.2, the step
 * 1 of M3-D in §26). Each tick is {@code env.tick()} and then the write phase, as {@code ShardWorld}
 * runs it at the end of the tick; the flush interval runs on a clock the test moves.
 */
@EnvTest
public class SynxOverlayCombinedIntegrationTest {
    private static final int Y = SynxTestMap.FEATURE_Y;

    /** The store, the loader and the instance of one test, the store on a clock and a disk the test holds. */
    private static final class World {
        final Env env;
        final Path root;
        final OverlayFixture.Clock clock = new OverlayFixture.Clock();
        final OverlayFixture.CountingDisk disk = new OverlayFixture.CountingDisk();
        final OverlayStore store;
        final SynxChunkLoader loader;
        final Instance instance;

        World(Env env, Path root, OverlayOptions options, int encodeThreads, int segmentRecords) throws Exception {
            this.env = env;
            this.root = root;
            final SynxTestMap map = SynxTestMap.get();
            this.store = OverlayStore.open(root, map.manifest, map.settings(), options,
                    OverlayFixture.tuning(encodeThreads, segmentRecords, clock, disk));
            this.loader = new SynxChunkLoader(SynxWorlds.mapped(map.buildDir, map.manifest, SynxTestMap.REGION),
                    SynxTestMap.REGION, SynxWorlds.WRITABLE, store, store);
            this.instance = SynxWorlds.instance(env.process(), loader);
        }

        World(Env env, Path root, OverlayOptions options) throws Exception {
            this(env, root, options, 2, OverlayTuning.SEGMENT_RECORDS);
        }

        SynxChunk load(int chunkX, int chunkZ) {
            return (SynxChunk) instance.loadChunk(chunkX, chunkZ).join();
        }

        void tick() {
            env.tick();
            store.tickEnd();
        }

        void ticks(int count) {
            for (int i = 0; i < count; i++) tick();
        }

        /** Every dirty chunk due: the clock past the flush interval, then ticks until nothing is dirty. */
        void flushAll(List<SynxChunk> chunks) {
            clock.advance(Duration.ofSeconds(61));
            SynxWorlds.tickUntil(env, () -> chunks.stream().noneMatch(SynxChunk::dirty), store::tickEnd);
        }

        /** The store closed by its final flush, and a new one opened on its directory. */
        OverlayStore reopen() throws Exception {
            store.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);
            final SynxTestMap map = SynxTestMap.get();
            return OverlayStore.open(root, map.manifest, map.settings(), new OverlayOptions(16, 10),
                    OverlayFixture.tuning(1, new OverlayFixture.Clock(), new OverlayFixture.CountingDisk()));
        }
    }

    /** The block state at a block of a record's segment ①. */
    private static int stateIn(OverlayHit hit, int x, int y, int z) {
        return stateIn(hit.record().packet(), x, y, z);
    }

    /** The block state at a block of a framed segment ①. */
    private static int stateIn(MemorySegment packet, int x, int y, int z) {
        final SynxPacketDecoder.Sections sections = SynxPacketDecoder.sections(packet,
                SynxTestMap.REGISTRIES, SynxTestMap.HEIGHT / 16);
        return sections.blocks()[y >> 4].get(x & 15, y & 15, z & 15);
    }

    private static int blockX(int chunkX) {
        return chunkX * 16 + 7;
    }

    private static int blockZ(int chunkZ) {
        return chunkZ * 16 + 7;
    }

    /** 场景「覆盖层达到上限时淘汰」. */
    @Test
    public void overTheCapTheLeastRecentlyChangedChunkIsDemotedOnItsRecord(Env env, @TempDir Path dir) throws Exception {
        final World world = new World(env, dir, new OverlayOptions(16, 1));
        final int z = 10;
        final List<SynxChunk> chunks = new ArrayList<>();
        for (int x = 1; x <= 17; x++) chunks.add(world.load(x, z));
        for (int x = 1; x <= 16; x++) world.instance.setBlock(blockX(x), Y, blockZ(z), Block.GOLD_BLOCK);
        world.flushAll(chunks.subList(0, 16));
        world.ticks(40);
        assertEquals(16, world.store.stats().resident());
        final SynxChunk second = chunks.get(1);
        final OverlayHit flushed = world.store.find(2, z);
        assertNotNull(flushed);
        assertEquals(second.generation(), flushed.generation());

        world.instance.setBlock(blockX(17), Y, blockZ(z), Block.GOLD_BLOCK);
        world.instance.setBlock(blockX(1) + 1, Y, blockZ(z), Block.DIAMOND_BLOCK);
        assertEquals(17, world.store.stats().resident());
        final int writes = world.disk.writes.get();
        world.tick();

        assertEquals(16, world.store.stats().resident(), "back to the cap at the end of the tick");
        assertEquals(SynxChunk.Mode.HANDLE, second.mode(), "chunk 2 changed least recently");
        for (int x = 1; x <= 17; x++) {
            if (x != 2) assertEquals(SynxChunk.Mode.OVERLAY, chunks.get(x - 1).mode(), "chunk " + x);
        }
        assertEquals(writes, world.disk.writes.get(), "no record appended for the clean chunk it demoted");
        final OverlayHit hit = world.store.find(2, z);
        assertNotNull(hit);
        assertEquals(flushed.segment(), hit.segment());
        assertEquals(flushed.offset(), hit.offset());
        final SendablePacket sent = second.getFullDataPacket();
        final BufferedPacket buffered = assertInstanceOf(BufferedPacket.class, sent, "a demoted chunk sends its record");
        final byte[] sentBytes = RecordingConnection.bytes(buffered);
        assertArrayEquals(flushed.record().packet().toArray(ValueLayout.JAVA_BYTE), sentBytes,
                "the bytes of its periodic flush record");
        assertEquals(Block.GOLD_BLOCK.stateId(), stateIn(MemorySegment.ofArray(sentBytes), blockX(2), Y, blockZ(z)),
                "a new viewer gets the change");

        world.instance.setBlock(blockX(2) + 1, Y, blockZ(z), Block.DIAMOND_BLOCK);
        assertEquals(SynxChunk.Mode.OVERLAY, second.mode());
        assertEquals(Block.GOLD_BLOCK, world.instance.getBlock(blockX(2), Y, blockZ(z)), "materialized from that record");
        assertEquals(flushed.generation() + 1, second.generation());
    }

    /** 场景「从覆盖记录重载的区块再改后重启」. */
    @Test
    public void aChunkReloadedFromItsRecordAndChangedAgainKeepsTheNewRecordAcrossARestart(Env env, @TempDir Path dir)
            throws Exception {
        final World world = new World(env, dir, new OverlayOptions(64, 1));
        final int cx = 20, cz = 20;
        SynxChunk chunk = world.load(cx, cz);
        for (int i = 0; i < 5; i++) world.instance.setBlock(blockX(cx) + i, Y, blockZ(cz), Block.GOLD_BLOCK);
        world.flushAll(List.of(chunk));
        world.instance.unloadChunk(chunk);
        final OverlayHit five = world.store.find(cx, cz);
        assertNotNull(five);
        assertEquals(5, five.generation());

        chunk = world.load(cx, cz);
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
        assertEquals(5, chunk.generation(), "the source is the record of generation 5");
        world.instance.setBlock(blockX(cx), Y + 1, blockZ(cz), Block.DIAMOND_BLOCK);
        world.flushAll(List.of(chunk));
        final OverlayHit six = world.store.find(cx, cz);
        assertNotNull(six);
        assertEquals(6, six.generation(), "the new record goes on from the one it was loaded from");

        final OverlayStore reopened = world.reopen();
        final OverlayHit after = reopened.find(cx, cz);
        assertNotNull(after);
        assertEquals(6, after.generation(), "the index of the reopened store names generation 6");
        assertEquals(Block.DIAMOND_BLOCK.stateId(), stateIn(after, blockX(cx), Y + 1, blockZ(cz)));
        assertEquals(Block.GOLD_BLOCK.stateId(), stateIn(after, blockX(cx) + 4, Y, blockZ(cz)));
    }

    /** 场景「显式卸载的脏区块不丢」. */
    @Test
    public void aDirtyChunkUnloadedExplicitlyKeepsItsChanges(Env env, @TempDir Path dir) throws Exception {
        final World world = new World(env, dir, new OverlayOptions(64, 10));
        final int cx = 21, cz = 20;
        final SynxChunk before = world.load(cx, cz);
        for (int i = 0; i < 3; i++) world.instance.setBlock(blockX(cx) + i, Y, blockZ(cz), Block.GOLD_BLOCK);
        assertTrue(before.dirty());
        assertEquals(3, before.generation());

        world.instance.unloadChunk(before);
        final SynxChunk reloaded = world.load(cx, cz);
        assertNotEquals(before, reloaded);
        assertEquals(SynxChunk.Mode.HANDLE, reloaded.mode());
        assertEquals(3, reloaded.generation());
        assertEquals(Block.GOLD_BLOCK, world.instance.getBlock(blockX(cx) + 2, Y, blockZ(cz)));
        final OverlayHit pending = world.store.find(cx, cz);
        assertNotNull(pending);
        assertEquals(-1, pending.segment(), "from the pending table until the write phase");

        final int forces = world.disk.forces.get();
        world.tick();
        assertEquals(forces + 1, world.disk.forces.get(), "appended and fsynced at the end of the tick");
        final OverlayHit written = world.store.find(cx, cz);
        assertNotNull(written);
        assertTrue(written.segment() >= 0, "the pending table is empty, the index names the record");
        assertEquals(3, written.generation());

        final OverlayStore reopened = world.reopen();
        final OverlayHit after = reopened.find(cx, cz);
        assertNotNull(after);
        assertEquals(Block.GOLD_BLOCK.stateId(), stateIn(after, blockX(cx) + 2, Y, blockZ(cz)));
    }

    /** 场景「判死时一个区块的锁被卡住」. */
    @Test
    public void aChunkLockHeldAtAFinalFlushByTheShutdownThreadIsTheOnlyOneLeft(Env env, @TempDir Path dir)
            throws Exception {
        final World world = new World(env, dir, new OverlayOptions(64, 10), 1, OverlayTuning.SEGMENT_RECORDS);
        final int cz = 22;
        final List<SynxChunk> chunks = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            chunks.add(world.load(x, cz));
            world.instance.setBlock(blockX(x), Y, blockZ(cz), Block.GOLD_BLOCK);
        }
        final SynxChunk stuck = chunks.get(7);
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = Thread.ofPlatform().start(() -> {
            stuck.lockWriteLock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                stuck.unlockWriteLock();
            }
        });
        held.await();
        try (LogCapture logs = LogCapture.of(OverlayStore.class)) {
            final long start = System.nanoTime();
            final CompletableFuture<?> done = world.store.finalFlush(Duration.ofSeconds(2), false);
            done.get(3, TimeUnit.SECONDS);
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "done within the 2 s");
            assertTrue(logs.messagesAt(Level.ERROR).stream().anyMatch(line -> line.startsWith("Overlay final flush:")
                    && line.contains("chunks=19") && line.contains("left=1")), logs.messagesAt(Level.ERROR).toString());
        } finally {
            release.countDown();
            holder.join();
        }
        final SynxTestMap map = SynxTestMap.get();
        final OverlayStore reopened = OverlayStore.open(dir, map.manifest, map.settings(), new OverlayOptions(64, 10),
                OverlayFixture.tuning(1, new OverlayFixture.Clock(), new OverlayFixture.CountingDisk()));
        for (int x = 0; x < 20; x++) {
            final OverlayHit hit = reopened.find(x, cz);
            if (x == 7) {
                assertNull(hit, "the stuck chunk is left out");
            } else {
                assertNotNull(hit, "chunk " + x);
                assertEquals(Block.GOLD_BLOCK.stateId(), stateIn(hit, blockX(x), Y, blockZ(cz)));
            }
        }
    }

    /** 场景「排队中的句柄包在覆盖段整理后仍可发出」. */
    @Test
    public void handlePacketsQueuedOnASegmentStillGoOutAfterItIsCompactedAway(Env env, @TempDir Path dir) throws Exception {
        final World world = new World(env, dir, new OverlayOptions(256, 1), 2, 64);
        final int cz = 24;
        final List<SynxChunk> chunks = new ArrayList<>();
        for (int x = 0; x < 64; x++) {
            chunks.add(world.load(x % 32, cz + x / 32));
            world.instance.setBlock(blockX(x % 32), Y, blockZ(cz + x / 32), Block.GOLD_BLOCK);
        }
        world.flushAll(chunks);
        final OverlayHit first = world.store.find(0, cz);
        assertNotNull(first);
        final int segment = first.segment();
        final Path segmentFile = OverlayFixture.segment(dir, SynxTestMap.get().manifest, segment);
        assertTrue(Files.exists(segmentFile));

        // Loaded again from their records: the handle packets of all 64 hold slices of that segment
        final List<SendablePacket> queued = new ArrayList<>();
        final List<byte[]> bytes = new ArrayList<>();
        for (int x = 0; x < 64; x++) {
            world.instance.unloadChunk(chunks.get(x));
            final SynxChunk handle = world.load(x % 32, cz + x / 32);
            final OverlayHit hit = world.store.find(x % 32, cz + x / 32);
            assertNotNull(hit);
            assertEquals(segment, hit.segment());
            final BufferedPacket packet = assertInstanceOf(BufferedPacket.class, handle.getFullDataPacket());
            queued.add(packet);
            bytes.add(RecordingConnection.bytes(packet));
            chunks.set(x, handle);
        }
        // 33 of the 64 written over: more than half of the segment is dead, compaction copies the rest away
        for (int x = 0; x < 33; x++) world.instance.setBlock(blockX(x % 32) + 1, Y, blockZ(cz + x / 32), Block.DIAMOND_BLOCK);
        world.flushAll(chunks.subList(0, 33));
        SynxWorlds.tickUntil(env, () -> !Files.exists(segmentFile), world.store::tickEnd);

        for (int x = 0; x < 64; x++) {
            assertArrayEquals(bytes.get(x), RecordingConnection.bytes((BufferedPacket) queued.get(x)), "packet " + x);
        }
        for (int x = 33; x < 64; x++) {
            final OverlayHit hit = world.store.find(x % 32, cz + x / 32);
            assertNotNull(hit);
            assertNotEquals(segment, hit.segment(), "copied out of the unlinked segment");
        }
    }

    /** 场景「覆盖存储写失败时不丢改动」. */
    @Test
    public void aWriteThatFailsLosesNoChange(Env env, @TempDir Path dir) throws Exception {
        final World world = new World(env, dir, new OverlayOptions(1, 1));
        world.instance.setChunkUnloadGraceTicks(1);
        final int cz = 26;
        final List<SynxChunk> chunks = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            chunks.add(world.load(x, cz));
            world.instance.setBlock(blockX(x), Y, blockZ(cz), Block.GOLD_BLOCK);
        }
        world.disk.failWrite = new IOException("No space left on device");
        world.clock.advance(Duration.ofSeconds(2));
        try (LogCapture logs = LogCapture.of(OverlayStore.class)) {
            world.ticks(10);
            assertTrue(logs.messagesAt(Level.ERROR).stream().anyMatch(line -> line.startsWith("The overlay write phase failed")),
                    logs.messagesAt(Level.ERROR).toString());
        }
        assertTrue(world.store.stats().evictFailed() > 0, "evict_failed counts the failures");
        for (int x = 0; x < 3; x++) {
            final SynxChunk chunk = chunks.get(x);
            assertSame(chunk, world.instance.getChunk(x, cz), "not unloaded: chunk " + x);
            assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode(), "chunk " + x);
            assertTrue(chunk.dirty(), "chunk " + x);
            assertEquals(Block.GOLD_BLOCK, world.instance.getBlock(blockX(x), Y, blockZ(cz)));
            assertNull(world.store.find(x, cz));
        }

        world.disk.failWrite = null;
        final long failed = world.store.stats().evictFailed();
        // The next write phase takes all three; an encoding past its 20 ms, on a loaded machine, waits one more
        SynxWorlds.tickUntil(env, () -> chunks.stream().noneMatch(SynxChunk::dirty), world.store::tickEnd);
        assertEquals(failed, world.store.stats().evictFailed(), "the write after the recovery succeeds");
        for (int x = 0; x < 3; x++) {
            final OverlayHit hit = world.store.find(x, cz);
            assertNotNull(hit, "written: chunk " + x);
            assertEquals(Block.GOLD_BLOCK.stateId(), stateIn(hit, blockX(x), Y, blockZ(cz)));
        }
    }

    /**
     * §19 「覆盖层写到磁盘上限后改方块被拒、标志照常」 (M3-S, {@code API-M3-007} 磁盘上限): once the segment
     * files hold {@code synaxis.overlay.max-bytes}, here 65,536, a change to a handle chunk and one to an
     * overlay chunk are left out, nothing changed and nothing dirty; a world state flag changes a third
     * chunk, which is written as before. One ERROR when the store turns full.
     */
    @Test
    public void aFullStoreRefusesChangesButNotTheWorldStateFlags(Env env, @TempDir Path dir) throws Exception {
        final World world = new World(env, dir, new OverlayOptions(16_384, 10, 65_536));
        final int z = 12;
        try (LogCapture logs = LogCapture.of(OverlayStore.class)) {
            // Chunks of rows 12, 14 and 16 written one after the other until the store is full; row 13 is left for the rest
            SynxChunk last = null;
            for (int i = 0; i < 90 && !world.store.full(); i++) {
                final int x = 1 + i % 30, row = z + 2 * (i / 30);
                last = world.load(x, row);
                world.instance.setBlock(blockX(x), Y, blockZ(row), Block.GOLD_BLOCK);
                world.flushAll(List.of(last));
            }
            assertNotNull(last);
            final SynxChunk handle = world.load(5, z + 1);
            final SynxChunk overlay = last;
            final long generation = overlay.generation();
            world.instance.setBlock(blockX(5), Y, blockZ(z + 1), Block.DIAMOND_BLOCK);
            world.instance.setBlock(blockX(overlay.chunkX()) + 1, Y, blockZ(overlay.chunkZ()), Block.DIAMOND_BLOCK);
            assertEquals(SynxChunk.Mode.HANDLE, handle.mode(), "the handle chunk did not change");
            assertNotEquals(Block.DIAMOND_BLOCK, world.instance.getBlock(blockX(5), Y, blockZ(z + 1)));
            assertNotEquals(Block.DIAMOND_BLOCK, world.instance.getBlock(blockX(overlay.chunkX()) + 1, Y, blockZ(overlay.chunkZ())));
            assertEquals(generation, overlay.generation(), "the overlay chunk did not change");
            assertTrue(!handle.dirty() && !overlay.dirty(), "nothing to write");

            final OverlayStats full = world.store.stats();
            assertTrue(full.full() && full.writtenBytes() >= 65_536, full.toString());
            final SynxChunk flagged = world.load(6, z + 1);
            SynxChunk.asWorldFlag(() -> world.instance.setBlock(blockX(6), Y, blockZ(z + 1), Block.EMERALD_BLOCK));
            assertEquals(Block.EMERALD_BLOCK, world.instance.getBlock(blockX(6), Y, blockZ(z + 1)), "the flag applies");
            assertTrue(flagged.dirty());
            world.flushAll(List.of(flagged));
            final OverlayHit written = world.store.find(6, z + 1);
            assertNotNull(written, "the flag's chunk is written");
            assertEquals(flagged.generation(), written.generation());
            assertTrue(world.store.stats().writtenBytes() > full.writtenBytes(), "the bytes written still grow");
            assertEquals(1, logs.messagesAt(Level.ERROR).stream().filter(line -> line.startsWith("Overlay store full: "))
                    .count(), logs.messagesAt(Level.ERROR).toString());
        }
    }
}
