package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.Section;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.biome.Biome;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §19 {@code REQ-M3-008}: what a {@link SynxChunk} holds once it is
 * materialized into overlay mode, and its side of the overlay contract of API-M3-005 against an
 * overlay store in memory.
 */
@EnvTest
public class SynxChunkOverlayIntegrationTest {
    /**
     * 场景「段① 与重新编码逐字节一致」, with its counter proof. Every chunk of the test map holds cave
     * air and void air ({@link SynxTestMap#OTHER_AIR_Y}), which the client and the baker count as air.
     */
    @Test
    public void aThousandMaterializedChunksEncodeBackToTheirSegmentOne(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final List<int[]> chunks = new ArrayList<>();
        for (int z = SynxTestMap.REGION.minZ(); z <= SynxTestMap.REGION.maxZ(); z++) {
            for (int x = SynxTestMap.REGION.minX(); x <= SynxTestMap.REGION.maxX(); x++) chunks.add(new int[]{x, z});
        }
        Collections.shuffle(chunks, new Random(20260927L));
        int equal = 0, equalWithoutLight = 0;
        for (int[] at : chunks.subList(0, 1_000)) {
            final SynxChunk chunk = (SynxChunk) instance.loadChunk(at[0], at[1]).join();
            final byte[] segment = SynxChunkPacket.body(map.record(at[0], at[1]).packet());
            assertEquals(Block.CAVE_AIR, chunk.getBlock(at[0] * 16, SynxTestMap.OTHER_AIR_Y, at[1] * 16));
            materialize(chunk);
            // What a new viewer is sent, framed as the write thread frames it, and the overlay record
            final boolean same = Arrays.equals(segment, SynxChunkPacket.body(RecordingConnection.framed(chunk.getFullDataPacket())))
                    && Arrays.equals(segment, SynxChunkPacket.body(encode(chunk, map).record().packet()));
            if (same) equal++;

            // Counter proof: the same chunk with the light arrays left out of the sections
            chunk.lockWriteLock();
            try {
                for (Section section : chunk.getSections()) {
                    section.setSkyLight(new byte[0]);
                    section.setBlockLight(new byte[0]);
                }
                chunk.invalidate();
            } finally {
                chunk.unlockWriteLock();
            }
            if (Arrays.equals(segment, SynxChunkPacket.body(RecordingConnection.framed(chunk.getFullDataPacket())))) {
                equalWithoutLight++;
            }
            instance.unloadChunk(chunk);
        }
        assertEquals(1_000, equal, "every materialized chunk sends and records its segment ①");
        assertEquals(0, equalWithoutLight, "without the light arrays every one differs (empty sky mask)");
    }

    /**
     * 场景「淘汰后已交出的包不变空」: the packet an overlay chunk hands a connection keeps the content
     * it was handed out with. Demoted before the write thread frames it, or framed again once the
     * frame is dropped under memory pressure, it still holds the chunk, not the empty sections handle
     * mode leaves behind.
     */
    @Test
    public void aPacketHandedOutBeforeADemotionKeepsItsContent(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final MemoryOverlay overlay = new MemoryOverlay();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
        final int cx = 16, cz = 16;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();
        instance.setBlock(cx * 16 + 8, SynxTestMap.FEATURE_Y, cz * 16 + 8, Block.GLASS);
        final SendablePacket queued = chunk.getFullDataPacket();

        final OverlayEncoding encoding = encode(chunk, map);
        chunk.written(encoding.generation(), overlay.store(encoding), true);
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());

        final byte[] expected = SynxChunkPacket.body(encoding.record().packet());
        final ChunkDataPacket framed = SynxChunkPacket.decode(RecordingConnection.framed(queued), SynxTestMap.REGISTRIES);
        assertFalse(framed.lightData().skyMask().isEmpty(), "framed after the demotion, still lit");
        assertArrayEquals(expected, canonicalBody(framed, map), "framed after the demotion");
        final ChunkDataPacket again = assertInstanceOf(ChunkDataPacket.class, RecordingConnection.serverPacket(queued));
        assertArrayEquals(expected, canonicalBody(again, map), "taken again, as after its frame is dropped");
    }

    /** 场景「物化后新观察者不变黑」. */
    @Test
    public void newViewersOfAMaterializedChunkKeepItsSkyLight(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final int cx = 16, cz = 16;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();
        final LightData baked = SynxChunkPacket.decode(map.record(cx, cz).packet(), SynxTestMap.REGISTRIES).lightData();
        assertFalse(baked.skyMask().isEmpty(), "the sections above the surface are lit");
        instance.setBlock(cx * 16 + 8, SynxTestMap.FEATURE_Y, cz * 16 + 8, Block.GLASS);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());

        final RecordingConnection connection = new RecordingConnection(env.process());
        final Player player = RecordingConnection.connect(env.process(), connection, instance,
                new Pos(cx * 16 + 8, 190, cz * 16 + 8));
        SynxWorlds.tickUntil(env, () -> connection.packets(ChunkDataPacket.class).stream()
                .anyMatch(packet -> packet.chunkX() == cx && packet.chunkZ() == cz), () -> player.onChunkBatchReceived(64));
        final ChunkDataPacket sent = connection.packets(ChunkDataPacket.class).stream()
                .filter(packet -> packet.chunkX() == cx && packet.chunkZ() == cz).findFirst().orElseThrow();
        assertEquals(baked.skyMask(), sent.lightData().skyMask());
        assertFalse(sent.lightData().emptySkyMask().intersects(baked.skyMask()), "no lit section goes dark");
        assertEquals(baked.skyLight().size(), sent.lightData().skyLight().size());
        for (int i = 0; i < baked.skyLight().size(); i++) {
            assertArrayEquals(baked.skyLight().get(i), sent.lightData().skyLight().get(i), "sky light array " + i);
        }
    }

    /** 场景「告示牌文字在物化后不丢」. */
    @Test
    public void signTextSurvivesMaterialization(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(SynxTestMap.SIGN_CHUNK_X, SynxTestMap.SIGN_CHUNK_Z).join();
        final CompoundBinaryTag expected = withoutRecordKeys(SynxTestMap.signNbt());
        final Block handle = instance.getBlock(SynxTestMap.SIGN_X, SynxTestMap.FEATURE_Y, SynxTestMap.SIGN_Z);
        assertEquals(Block.OAK_SIGN.stateId(), handle.stateId());
        assertEquals(expected, handle.nbt(), "handle mode reads segment ③");

        instance.setBlock(SynxTestMap.SIGN_X + 2, SynxTestMap.FEATURE_Y, SynxTestMap.SIGN_Z, Block.STONE);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
        final Block overlay = instance.getBlock(SynxTestMap.SIGN_X, SynxTestMap.FEATURE_Y, SynxTestMap.SIGN_Z);
        assertEquals(Block.OAK_SIGN.stateId(), overlay.stateId());
        assertEquals(expected, overlay.nbt());
        assertEquals(StringBinaryTag.stringBinaryTag("Synaxis"),
                overlay.nbt().getCompound("front_text").getList("messages").get(0));

        final OverlayEncoding encoding = chunk.encodeOverlay(map.settings(), System.nanoTime() + 1_000_000_000L);
        assertNotNull(encoding);
        final SynxBlockEntity sign = encoding.record().blockEntities().stream()
                .filter(entity -> entity.index() == SynxBlockEntity.index(SynxTestMap.SIGN_X, SynxTestMap.FEATURE_Y, SynxTestMap.SIGN_Z))
                .findFirst().orElseThrow();
        assertEquals(expected, sign.nbt(), "the overlay record keeps the sign");
        assertEquals("", sign.handler());
    }

    /** 场景「分片没登记的 handler 命名空间不丢」. */
    @Test
    public void unregisteredHandlerNamespacesSurviveTheOverlay(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final int x = SynxTestMap.UNKNOWN_X, y = SynxTestMap.FEATURE_Y, z = SynxTestMap.UNKNOWN_Z;
        final OverlayEncoding encoding;
        try (LogCapture logs = LogCapture.of(BlockManager.class)) {
            final SynxChunk chunk = (SynxChunk) instance.loadChunk(SynxTestMap.UNKNOWN_CHUNK_X, SynxTestMap.UNKNOWN_CHUNK_Z).join();
            final Block handle = instance.getBlock(x, y, z);
            assertNotNull(handle.handler());
            assertEquals(SynxTestMap.UNKNOWN_HANDLER, handle.handler().getKey().asString());

            instance.setBlock(x + 2, y, z, Block.STONE);
            assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
            final Block overlay = instance.getBlock(x, y, z);
            assertNotNull(overlay.handler());
            assertEquals(SynxTestMap.UNKNOWN_HANDLER, overlay.handler().getKey().asString());
            encoding = chunk.encodeOverlay(map.settings(), System.nanoTime() + 1_000_000_000L);
            assertNotNull(encoding);

            final List<String> warnings = logs.messagesAt(Level.WARN).stream()
                    .filter(line -> line.contains(SynxTestMap.UNKNOWN_HANDLER)).toList();
            assertEquals(1, warnings.size(), warnings.toString());
        }
        final SynxBlockEntity chest = encoding.record().blockEntities().stream()
                .filter(entity -> entity.index() == SynxBlockEntity.index(x, y, z)).findFirst().orElseThrow();
        assertEquals(SynxTestMap.UNKNOWN_HANDLER, chest.handler(), "the namespace stays in segment ③");
        assertEquals(withoutRecordKeys(SynxTestMap.unknownChestNbt()), chest.nbt());
        // What the client gets: the dummy handler declares no client tag
        final ChunkDataPacket decoded = SynxChunkPacket.decode(encoding.record().packet(), SynxTestMap.REGISTRIES);
        final Block client = decoded.chunkData().blockEntities().values().stream()
                .filter(block -> block.stateId() == Block.CHEST.stateId()).findFirst().orElseThrow();
        assertEquals(CompoundBinaryTag.empty(), client.nbt(), "no server side NBT on the wire");
    }

    /** API-M3-005: {@code encodeOverlay} waits at most 50 ms for the write-lock, then gives up. */
    @Test
    public void encodeOverlayGivesUpAfter50msWhenTheLockIsHeld(Env env) throws Exception {
        final SynxTestMap map = SynxTestMap.get();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(2, 2).join();
        instance.setBlock(40, SynxTestMap.FEATURE_Y, 40, Block.STONE);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = holdWriteLock(chunk, release);
        try {
            final long start = System.nanoTime();
            assertNull(chunk.encodeOverlay(map.settings(), start + TimeUnit.SECONDS.toNanos(2)));
            final long waited = System.nanoTime() - start;
            assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(50), "waited " + waited + " ns");
            assertTrue(waited < TimeUnit.SECONDS.toNanos(1), "50 ms, not the 2 s to the deadline: " + waited + " ns");

            final long early = System.nanoTime();
            assertNull(chunk.encodeOverlay(map.settings(), early + TimeUnit.MILLISECONDS.toNanos(10)));
            assertTrue(System.nanoTime() - early < TimeUnit.MILLISECONDS.toNanos(45), "an earlier deadline ends the wait");
        } finally {
            release.countDown();
            holder.join();
        }
        final OverlayEncoding encoding = chunk.encodeOverlay(map.settings(), System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        assertNotNull(encoding, "the lock is free again");
        assertEquals(1, encoding.generation());
    }

    /**
     * API-M3-005 {@code written} waits at most 50 ms for the write-lock as well, so that a tick thread
     * judged dead while it holds one cannot hold the final flush past its budget. The chunk then stays
     * as it was, overlay and dirty, and is written again.
     */
    @Test
    public void writtenGivesUpAfter50msWhenTheLockIsHeld(Env env) throws Exception {
        final SynxTestMap map = SynxTestMap.get();
        final MemoryOverlay overlay = new MemoryOverlay();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
        final int cx = 13, cz = 13;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();
        instance.setBlock(cx * 16, SynxTestMap.FEATURE_Y, cz * 16, Block.STONE);
        final OverlayEncoding encoding = encode(chunk, map);
        final OverlayHit hit = overlay.store(encoding);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = holdWriteLock(chunk, release);
        try (LogCapture logs = LogCapture.of(SynxChunk.class)) {
            final long start = System.nanoTime();
            assertFalse(CompletableFuture.supplyAsync(() -> chunk.written(encoding.generation(), hit, true))
                    .get(1, TimeUnit.SECONDS), "not taken without the lock");
            final long waited = System.nanoTime() - start;
            assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(50), "waited " + waited + " ns");
            assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
            assertTrue(chunk.dirty());
            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains(cx + "," + cz), warnings.getFirst());
        } finally {
            release.countDown();
            holder.join();
        }
        assertTrue(chunk.written(encoding.generation(), hit, true), "taken once the lock is free");
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode(), "written again once the lock is free");
        assertFalse(chunk.dirty());
    }

    /**
     * A change the engine throws out of halfway, from a handler's {@code onPlace} say, counts all the
     * same: the palette has it. A biome that is not registered throws before it changes anything, but
     * the chunk it materialized is dirty and written like any other.
     */
    @Test
    public void aChangeThatThrowsCountsAllTheSame(Env env) {
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final int cx = 14, cz = 14, x = cx * 16 + 4, y = SynxTestMap.FEATURE_Y, z = cz * 16 + 4;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();
        final BlockHandler throwing = new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key("synaxis:throws");
            }

            @Override
            public void onPlace(Placement placement) {
                throw new IllegalStateException("onPlace");
            }
        };
        assertThrows(IllegalStateException.class, () -> instance.setBlock(x, y, z, Block.STONE.withHandler(throwing)));
        assertEquals(Block.STONE.stateId(), instance.getBlock(x, y, z).stateId(), "the palette has the change");
        assertEquals(1, chunk.generation());
        assertTrue(chunk.dirty());

        final SynxChunk other = (SynxChunk) instance.loadChunk(cx + 1, cz).join();
        assertThrows(IllegalStateException.class,
                () -> instance.setBiome(x + 16, y, z, RegistryKey.unsafeOf("synaxis:nowhere")));
        assertEquals(SynxChunk.Mode.OVERLAY, other.mode());
        assertEquals(1, other.generation());
        assertTrue(other.dirty());
    }

    /** API-M3-005 {@code written}: back to handle mode on the new record, unless changed since. */
    @Test
    public void writtenDemotesOnlyWhatDidNotChangeSince(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final MemoryOverlay overlay = new MemoryOverlay();
        final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, overlay);
        final Instance instance = SynxWorlds.instance(env.process(), loader);
        final int cx = 3, cz = 4, x = cx * 16 + 2, y = SynxTestMap.FEATURE_Y, z = cz * 16 + 2;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();

        instance.setBlock(x, y, z, Block.GOLD_BLOCK);
        assertEquals(1, chunk.generation());
        final OverlayEncoding first = encode(chunk, map);
        final OverlayHit firstHit = overlay.store(first);
        chunk.written(first.generation(), firstHit, true);
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
        assertFalse(chunk.dirty());
        assertEquals(1, chunk.generation());
        assertEquals(Block.GOLD_BLOCK, instance.getBlock(x, y, z), "handle mode reads the overlay record now");
        assertArrayEquals(first.record().packet(), RecordingConnection.bytes((BufferedPacket) chunk.getFullDataPacket()));

        // Changed after the encoding: stays overlay and dirty
        instance.setBlock(x, y, z, Block.DIAMOND_BLOCK);
        final OverlayEncoding second = encode(chunk, map);
        instance.setBlock(x, y, z, Block.EMERALD_BLOCK);
        assertEquals(3, chunk.generation());
        chunk.written(second.generation(), overlay.store(second), true);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
        assertTrue(chunk.dirty());
        assertEquals(Block.EMERALD_BLOCK, instance.getBlock(x, y, z));

        // Written without demotion: clean overlay; then demoted without a new encoding, as a clean eviction
        final OverlayEncoding third = encode(chunk, map);
        final OverlayHit thirdHit = overlay.store(third);
        chunk.written(third.generation(), thirdHit, false);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
        assertFalse(chunk.dirty());
        chunk.written(third.generation(), thirdHit, true);
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
        assertEquals(Block.EMERALD_BLOCK, instance.getBlock(x, y, z));

        // A handle chunk again: the next change materializes from the overlay record
        instance.setBlock(x + 1, y, z, Block.STONE);
        assertEquals(4, chunk.generation());
        assertEquals(Block.EMERALD_BLOCK, instance.getBlock(x, y, z));
        assertEquals(List.of(0L, 1L, 3L), overlay.events("materialized").stream().map(MemoryOverlay.Event::generation).toList());
    }

    /**
     * {@link SynxChunk#sameBlock}, which a world state flag compares with (API-M3-013): a block entity
     * read back from its overlay record is the block that was set, whatever NBT form, record keys or
     * handler object it comes back with; the state, the other NBT keys and the handler namespace still
     * tell two blocks apart.
     */
    @Test
    public void aBlockEntityReadBackFromItsRecordIsTheSameBlock(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final MemoryOverlay overlay = new MemoryOverlay();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
        final Key handlerKey = Key.key("synaxis_test", "same_block");
        // A new handler on every call, as a registration may make them
        env.process().block().registerHandler(handlerKey, () -> (BlockHandler) () -> handlerKey);
        final int cx = 3, cz = 4, x = cx * 16 + 2, y = SynxTestMap.FEATURE_Y, z = cz * 16 + 2;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();
        final List<Block> set = List.of(Block.CHEST,
                Block.CHEST.withNbt(CompoundBinaryTag.builder().putString("id", "minecraft:chest").putInt("x", x + 1)
                        .putBoolean("keepPacked", false).putString("note", "kept").build()),
                Block.CHEST.withHandler(() -> handlerKey));
        for (int i = 0; i < set.size(); i++) instance.setBlock(x + i, y, z, set.get(i));
        final OverlayEncoding encoding = encode(chunk, map);
        chunk.written(encoding.generation(), overlay.store(encoding), true);
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());

        for (int i = 0; i < set.size(); i++) {
            final Block back = instance.getBlock(x + i, y, z);
            assertNotEquals(set.get(i), back, "precondition: the record gives the block back in another form");
            assertTrue(SynxChunk.sameBlock(back, set.get(i)), back.toString());
        }
        final Block back = instance.getBlock(x + 1, y, z);
        assertFalse(SynxChunk.sameBlock(back, Block.CHEST.withNbt(CompoundBinaryTag.builder().putString("note", "changed").build())));
        assertFalse(SynxChunk.sameBlock(back, Block.CHEST.withProperty("facing", "south").withNbt(back.nbt())));
        final Block handled = instance.getBlock(x + 2, y, z);
        assertFalse(SynxChunk.sameBlock(handled, Block.CHEST), "a handler against none");
        assertFalse(SynxChunk.sameBlock(handled, Block.CHEST.withHandler(BlockHandler.Dummy.get("synaxis_test:other"))));
    }

    /** API-M3-005 卸载前检查 and the unload notification. */
    @Test
    public void aDirtyChunkAsksForAFlushBeforeItUnloads(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final MemoryOverlay overlay = new MemoryOverlay();
        final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, overlay);
        final Instance instance = SynxWorlds.instance(env.process(), loader);
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(4, 4).join();
        assertTrue(chunk.readyToUnload(), "handle");
        instance.setBlock(4 * 16, SynxTestMap.FEATURE_Y, 4 * 16, Block.STONE);
        assertFalse(chunk.readyToUnload(), "dirty overlay");
        assertEquals(1, overlay.events("unloadRequested").size());
        final OverlayEncoding encoding = encode(chunk, map);
        chunk.written(encoding.generation(), overlay.store(encoding), false);
        assertTrue(chunk.readyToUnload(), "clean overlay");

        instance.unloadChunk(chunk);
        assertEquals(List.of(new MemoryOverlay.Event("unloaded", 4, 4, 1, false)), overlay.events("unloaded"));
        assertEquals(1, loader.stats().unloaded());
    }

    /**
     * Track A's half of 场景「显式卸载的脏区块不丢」: {@code unloaded} comes under the instance monitor,
     * without the chunk's lock, so the store can encode the dirty chunk right there; loaded again from
     * that pending encoding, the chunk has the change and goes on from its {@code generation}.
     */
    @Test
    public void anExplicitlyUnloadedDirtyChunkCanBeEncodedOnTheSpot(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final MemoryOverlay overlay = new MemoryOverlay();
        final AtomicLong sequence = new AtomicLong();
        overlay.onUnloaded = chunk -> {
            if (!chunk.dirty()) return;
            final OverlayEncoding encoding = chunk.encodeOverlay(map.settings(), System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            assertNotNull(encoding, "the unload holds the instance monitor, not the chunk's lock");
            overlay.put(MemoryOverlay.hit(encoding.record(), encoding.generation(), -1, sequence.incrementAndGet()));
        };
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
        final int cx = 10, cz = 10, x = cx * 16 + 7, y = SynxTestMap.FEATURE_Y, z = cz * 16 + 7;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();
        instance.setBlock(x, y, z, Block.GOLD_BLOCK);
        instance.setBlock(x, y + 1, z, Block.GOLD_BLOCK);
        instance.setBlock(x, y + 2, z, Block.GOLD_BLOCK);
        assertEquals(3, chunk.generation());

        instance.unloadChunk(chunk);
        assertEquals(List.of(new MemoryOverlay.Event("unloaded", cx, cz, 3, true)), overlay.events("unloaded"));
        final SynxChunk again = (SynxChunk) instance.loadChunk(cx, cz).join();
        assertEquals(SynxChunk.Mode.HANDLE, again.mode());
        assertEquals(3, again.generation());
        assertEquals(Block.GOLD_BLOCK, instance.getBlock(x, y + 2, z));
        instance.setBlock(x, y + 3, z, Block.GOLD_BLOCK);
        assertEquals(4, again.generation());
    }

    /** A biome change materializes and counts like a block change; a handle chunk encodes to its source. */
    @Test
    public void biomeChangesMaterializeToo(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final MemoryOverlay overlay = new MemoryOverlay();
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
        final int cx = 11, cz = 11, x = cx * 16, y = 100, z = cz * 16;
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();

        final OverlayEncoding untouched = encode(chunk, map);
        assertEquals(0, untouched.generation());
        final SynxChunkRecord record = map.record(cx, cz);
        assertArrayEquals(record.packet(), untouched.record().packet(), "handle mode encodes to the record it reads");
        assertArrayEquals(record.navigation(), untouched.record().navigation());
        assertEquals(record.blockEntities(), untouched.record().blockEntities());
        assertEquals(record.staticEntities(), untouched.record().staticEntities());
        assertEquals(0, untouched.record().columns().length, "overlay records have no segment ④");

        final RegistryKey<Biome> before = instance.getBiome(x, y, z);
        final RegistryKey<Biome> other = before.equals(Biome.DESERT) ? Biome.PLAINS : Biome.DESERT;
        instance.setBiome(x, y, z, other);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
        assertEquals(1, chunk.generation());
        assertTrue(chunk.dirty());
        assertEquals(other, instance.getBiome(x, y, z));
        assertEquals(before, instance.getBiome(x + 8, y, z + 8), "the rest of the chunk keeps its biome");
        final ChunkDataPacket sent = packet(chunk);
        final ChunkDataPacket decoded = SynxChunkPacket.decode(encode(chunk, map).record().packet(), SynxTestMap.REGISTRIES);
        assertArrayEquals(sent.chunkData().data(), decoded.chunkData().data(), "the new biome is in the record");
    }

    /** {@code section_cache_bytes} bounds the decoded palettes; the counters of the loader. */
    @Test
    public void theSectionCacheStaysWithinItsBudget(Env env) throws Exception {
        final SynxChunkLoader small = SynxWorlds.loader(SynxTestMap.get().buildDir, SynxTestMap.REGION,
                new SynxOptions(true, 64 * 1024, 1, false), new MemoryOverlay());
        final Instance instance = SynxWorlds.instance(env.process(), small);
        for (int cx = 0; cx < 8; cx++) {
            instance.loadChunk(cx, 20).join();
            instance.getBlock(cx * 16, 64, 20 * 16);
            assertTrue(small.stats().sectionCacheBytes() > 0);
            assertTrue(small.stats().sectionCacheBytes() <= 64 * 1024 || cx == 0,
                    "over budget only while one chunk alone is larger: " + small.stats().sectionCacheBytes());
        }
        final SynxStreamStats stats = small.stats();
        assertEquals(8, stats.loaded());
        assertEquals(0, stats.unloaded());
        assertEquals(2, stats.regionsMapped());
        assertEquals(Files.size(SynxTestMap.regionFile(SynxTestMap.get().buildDir, 0, 0))
                + Files.size(SynxTestMap.regionFile(SynxTestMap.get().buildDir, 32, 0)), stats.bytesMapped());
        // Evicted palettes decode again, the same blocks
        for (int cx = 0; cx < 8; cx++) {
            assertEquals(Block.BEDROCK, instance.getBlock(cx * 16, 0, 20 * 16));
        }
    }

    private static OverlayEncoding encode(SynxChunk chunk, SynxTestMap map) {
        final OverlayEncoding encoding = chunk.encodeOverlay(map.settings(), System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        assertNotNull(encoding);
        assertEquals(chunk.generation(), encoding.generation());
        return encoding;
    }

    /** A platform thread that holds the write-lock of {@code chunk} until {@code release}; it holds it on return. */
    private static Thread holdWriteLock(SynxChunk chunk, CountDownLatch release) throws InterruptedException {
        final CountDownLatch held = new CountDownLatch(1);
        final Thread holder = Thread.ofPlatform().start(() -> {
            chunk.lockWriteLock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                chunk.unlockWriteLock();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        return holder;
    }

    /** Materializes a chunk without changing it: sets a block to what it already is. */
    private static void materialize(SynxChunk chunk) {
        final int x = chunk.getChunkX() * 16, z = chunk.getChunkZ() * 16;
        chunk.lockWriteLock();
        try {
            chunk.setBlock(x, 0, z, chunk.getBlock(x, 0, z));
        } finally {
            chunk.unlockWriteLock();
        }
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
    }

    private static ChunkDataPacket packet(SynxChunk chunk) {
        return assertInstanceOf(ChunkDataPacket.class, RecordingConnection.serverPacket(chunk.getFullDataPacket()));
    }

    /** The body of {@code packet} with its palettes in canonical order: equal when the content is. */
    private static byte[] canonicalBody(ChunkDataPacket packet, SynxTestMap map) {
        return SynxChunkPacket.body(SynxChunkPacket.encode(packet, map.settings(), SynxTestMap.REGISTRIES));
    }

    /** A block entity's NBT without the keys of the record around it, which the block itself carries. */
    static CompoundBinaryTag withoutRecordKeys(CompoundBinaryTag nbt) {
        CompoundBinaryTag result = nbt;
        for (String key : List.of("id", "x", "y", "z", "keepPacked")) result = result.remove(key);
        return result;
    }
}
