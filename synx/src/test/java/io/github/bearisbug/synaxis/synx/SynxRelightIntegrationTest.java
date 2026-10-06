package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.biome.Biome;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §19 {@code REQ-M3-012}, the {@code :synx} integration scenarios of the
 * runtime light: the {@link RelightTestMap} on a real {@link OverlayStore}, the recomputation on a
 * pool of its own or, where a scenario holds it back, on the test thread ({@link RelightWorld.ManualExecutor}).
 * The light a scenario checks is the light a new viewer gets, against the server's
 * ({@link RelightWorld#truth}).
 */
@EnvTest
public class SynxRelightIntegrationTest {
    private static final int Y = RelightTestMap.FLOOR;

    /** The block in the middle of chunk (x, z), on the surface. */
    private static int center(int chunk) {
        return chunk * 16 + 8;
    }

    /** 场景「改方块后 3×3 光照等于重算结果」. */
    @Test
    public void aTorchLightsTheThreeByThreeAsTheServerComputesIt(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, false);
        world.loadAround(3, 3, 2);
        final Map<String, Long> generations = new HashMap<>();
        for (int[] side : new int[][]{{2, 3}, {4, 3}, {3, 2}, {3, 4}}) {
            generations.put(side[0] + "," + side[1], world.chunk(side[0], side[1]).generation());
        }

        world.instance.setBlock(center(3), Y, center(3), Block.TORCH);
        world.settle();

        world.assertLight(2, 2, 4, 4);
        for (int[] side : new int[][]{{2, 3}, {4, 3}, {3, 2}, {3, 4}}) {
            final SynxChunk chunk = world.chunk(side[0], side[1]);
            assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode(), "the torch lights " + side[0] + "," + side[1]);
            assertTrue(chunk.dirty(), side[0] + "," + side[1]);
            assertTrue(chunk.generation() > generations.get(side[0] + "," + side[1]), side[0] + "," + side[1]);
        }
        for (int[] corner : new int[][]{{2, 2}, {4, 2}, {2, 4}, {4, 4}}) {
            assertEquals(SynxChunk.Mode.HANDLE, world.chunk(corner[0], corner[1]).mode(),
                    "the torch's light does not reach " + corner[0] + "," + corner[1]);
        }
    }

    /** 场景「光照装回后落盘，淘汰与重启后仍是新光照」. */
    @Test
    public void installedLightIsWrittenAndSurvivesTheEvictionAndARestart(Env env, @TempDir Path dir) throws Exception {
        final RelightWorld world = RelightWorld.create(env, dir, RelightTestMap.BOUNDS, RelightWorld.options(false),
                new OverlayOptions(1, 1), false);
        world.loadAround(3, 3, 2);
        world.instance.setBlock(center(3), Y, center(3), Block.TORCH);
        world.settle();
        assertTrue(world.lightMatches(3, 4), "installed");

        // Written, and every overlay chunk but the one the cap keeps back in handle mode on its record
        world.tickUntil(() -> {
            int overlay = 0;
            for (int x = 2; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    final SynxChunk chunk = world.chunk(x, z);
                    if (chunk.dirty()) return false;
                    if (chunk.mode() == SynxChunk.Mode.OVERLAY) overlay++;
                }
            }
            return overlay <= 1;
        });
        world.store.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);

        final RelightTestMap map = RelightTestMap.get();
        final OverlayStore reopened = OverlayStore.open(dir, map.manifest, map.settings(), new OverlayOptions(16, 10));
        final SynxChunkLoader loader = new SynxChunkLoader(SynxWorlds.mapped(map.buildDir, map.manifest, RelightTestMap.BOUNDS),
                RelightTestMap.BOUNDS, RelightWorld.options(false), reopened, reopened);
        final var instance = env.process().instance().createInstanceContainer(RelightWorld.dimension(env.process()), loader);
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) instance.loadChunk(x, z).join();
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                RelightWorld.assertLight("reloaded " + x + "," + z, RelightWorld.sentLight((SynxChunk) instance.getChunk(x, z)),
                        RelightWorld.truth(instance, x, z), x, z);
            }
        }
        assertEquals(Block.TORCH, instance.getBlock(center(3), Y, center(3)));
    }

    /** 场景「光照重算期间 3×3 里又改了方块」. */
    @Test
    public void aChangeInTheThreeByThreeWhileARecomputationRunsSkipsTheTargetsItCovers(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        world.loadAround(3, 3, 3);
        world.instance.setBlock(center(3), Y, center(3), Block.TORCH);
        final RelightWorld.ManualExecutor executor = world.executor;
        assertNotNull(executor);
        assertTrue(executor.runNext(), "the snapshot of center 3,3");
        final long skipped = world.relight.stats().skipped();

        world.instance.setBlock(center(4), Y, center(3), Block.GLOWSTONE);
        world.settle();

        assertTrue(world.relight.stats().skipped() - skipped >= 6,
                "the six targets of 3,3 whose 3×3 holds 4,3 (x 3 and 4) are skipped");
        world.assertLight(2, 2, 5, 4);
    }

    /** 场景「同一中心的多次改动合并成一次重算」. */
    @Test
    public void changesAroundOneCenterMergeIntoOneRecomputation(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        world.loadAround(3, 3, 2);
        final long relights = world.relight.stats().relights();
        for (Block block : List.of(Block.TORCH, Block.AIR, Block.TORCH, Block.AIR, Block.TORCH)) {
            world.instance.setBlock(center(3), Y, center(3), block);
        }
        world.settle();

        assertEquals(relights + 1, world.relight.stats().relights(), "five changes of center 3,3, one recomputation");
        world.assertLight(2, 2, 4, 4);
    }

    /** 场景「只改方块实体数据或生物群系不重算」. */
    @Test
    public void onlyTheDataOfABlockEntityOrABiomeChangedIsNotRecomputed(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, false);
        world.loadAround(3, 3, 2);
        final long before = world.relight.stats().relights();
        world.instance.setBlock(center(3), Y, center(3), Block.CHEST);
        world.settle();
        final long relights = world.relight.stats().relights();
        assertTrue(relights > before, "placing the chest is recomputed");

        world.instance.setBlock(center(3), Y, center(3), Block.CHEST.withNbt(CompoundBinaryTag.builder()
                .putString("LootTable", "minecraft:chests/simple_dungeon").build()));
        world.instance.setBiome(center(3) + 3, Y, center(3), Biome.DESERT);
        assertEquals(0, world.relight.pending(3, 3), "nothing queued");
        assertEquals(0, world.relight.pendingCenters());
        world.ticks(5);
        assertEquals(relights, world.relight.stats().relights());
    }

    /** 场景「重算中的区块不被淘汰、不被自动卸载」. */
    @Test
    public void theChunksOfARecomputationAreNeitherEvictedNorUnloaded(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, RelightTestMap.BOUNDS, RelightWorld.options(false),
                new OverlayOptions(1, 10), true);
        world.instance.setChunkUnloadGraceTicks(1);
        world.loadAround(3, 3, 2);
        final Map<Long, Chunk> threeByThree = new HashMap<>();
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) threeByThree.put(RelightWorld.key(x, z), world.chunk(x, z));
        }
        // 2,2 goes overlay first, without a recomputation of its own: the least recently changed of the two residents
        world.instance.setBiome(center(2), Y, center(2), Biome.DESERT);
        world.instance.setBlock(center(3), Y, center(3), Block.TORCH);
        assertEquals(2, world.store.stats().resident());

        world.ticks(20);
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                assertSame(threeByThree.get(RelightWorld.key(x, z)), world.instance.getChunk(x, z),
                        "chunk " + x + "," + z + " is still the one loaded");
            }
        }
        assertEquals(SynxChunk.Mode.OVERLAY, world.chunk(2, 2).mode(), "not evicted over the cap of 1");
        assertEquals(SynxChunk.Mode.OVERLAY, world.chunk(3, 3).mode(), "not evicted over the cap of 1");
        final List<String> ring = new ArrayList<>();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                if (Math.abs(x - 3) == 2 || Math.abs(z - 3) == 2) {
                    if (world.instance.getChunk(x, z) != null) ring.add(x + "," + z);
                }
            }
        }
        assertEquals(List.of(), ring, "the ring of the 5×5 is unloaded after its grace period");

        final RelightWorld.ManualExecutor executor = world.executor;
        assertNotNull(executor);
        assertTrue(executor.runNext(), "the snapshot");
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                assertNotNull(world.instance.getChunk(x, z), "loaded again for the snapshot: " + x + "," + z);
                assertTrue(world.instance.chunkTicketCount(x, z) >= 1, "held by the recomputation: " + x + "," + z);
            }
        }
        world.settle();
        world.tickUntil(() -> {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) if (world.instance.getChunk(x, z) != null) return false;
            }
            return true;
        });
        for (int[] at : new int[][]{{3, 3}, {2, 2}, {2, 3}, {4, 3}, {3, 2}, {3, 4}}) {
            assertNotNull(world.store.find(at[0], at[1]), "written before it was unloaded: " + at[0] + "," + at[1]);
        }
    }

    /** 场景「大区外的只读邻居不写光照」. */
    @Test
    public void aReadOnlyNeighbourPastTheRegionKeepsItsLight(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, new SynxManifest.Bounds(0, 0, 5, 7),
                RelightWorld.options(false), new OverlayOptions(16_384, 10), false);
        world.loadAround(5, 3, 2);
        final LightData before = RelightWorld.sentLight(world.chunk(6, 3));
        final long skipped = world.relight.stats().skipped();

        world.instance.setBlock(5 * 16 + 15, Y, center(3), Block.TORCH);
        world.settle();

        final SynxChunk neighbour = world.chunk(6, 3);
        assertTrue(neighbour.isReadOnly());
        assertEquals(SynxChunk.Mode.HANDLE, neighbour.mode());
        final LightData after = RelightWorld.sentLight(neighbour);
        assertTrue(RelightWorld.sameLight(before, after), "the light of 6,3 is as baked");
        assertTrue(world.relight.stats().skipped() - skipped >= 3, "6,2 6,3 and 6,4 are skipped");
        world.assertLight(4, 2, 5, 4);
    }

    /** 场景「引擎补的空气区块按缺失」. */
    @Test
    public void anAirChunkTheEngineMadeIsMissing(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, false);
        final Chunk air = world.instance.loadChunk(-1, 3).join();
        assertTrue(!(air instanceof SynxChunk), "the loader has nothing for -1,3: " + air);
        world.loadAround(0, 3, 2);
        final long relights = world.relight.stats().relights();

        final int z = 56;
        for (int x = 0; x <= 3; x++) {
            for (int y = 30; y <= 31; y++) world.instance.setBlock(x, y, z, Block.AIR);
        }
        world.settle();

        assertTrue(world.relight.stats().relights() > relights, "the tunnel is recomputed");
        final LightData light = RelightWorld.sentLight(world.chunk(0, 3));
        final byte[] sky = RelightWorld.array(light.skyMask(), light.emptySkyMask(), light.skyLight(), 1);
        assertNotNull(sky);
        for (int x = 0; x <= 3; x++) {
            for (int y = 30; y <= 31; y++) {
                final int index = x | (z & 15) << 4 | (y & 15) << 8;
                assertEquals(0, (sky[index >> 1] >> ((index & 1) << 2)) & 15, "sky light in the tunnel at x " + x + " y " + y);
            }
        }
        world.assertLight(0, 3);
    }

    /** 场景「装回每 tick 有上限」. */
    @Test
    public void anInstallHandlesAtMost64TargetEntriesATick(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        world.loadAround(3, 2, 3);
        world.loadAround(4, 3, 3);
        final long skipped = world.relight.stats().skipped();
        for (int x = 2; x <= 5; x++) {
            for (int z = 2; z <= 3; z++) world.instance.setBlock(center(x), Y, center(z), Block.TORCH);
        }
        final RelightWorld.ManualExecutor executor = world.executor;
        assertNotNull(executor);
        executor.runAll();

        final List<Integer> perTick = new ArrayList<>();
        for (int tick = 0; tick < 100 && (world.relight.pendingCenters() > 0 || perTick.isEmpty()); tick++) {
            env.tick();
            perTick.add(world.relight.installPhase());
            world.store.tickEnd();
        }
        assertEquals(72, perTick.stream().mapToInt(Integer::intValue).sum(), "eight centers, 72 target entries: " + perTick);
        assertTrue(perTick.stream().allMatch(count -> count <= 64), perTick.toString());
        assertTrue(perTick.stream().filter(count -> count > 0).count() >= 2, "at least two ticks: " + perTick);
        final RelightStats stats = world.relight.stats();
        assertEquals(skipped, stats.skipped(), "every snapshot came after the eight changes");
        assertTrue(stats.installMsMax() > 0, "relight_install_ms_max: " + stats.installMsMax());
        world.assertLight(1, 1, 6, 4);
    }

    /** 场景「排空预算为 0 时以旧光照落盘」. */
    @Test
    public void aDrainWithNoBudgetLeavesTheOldLightToTheFinalFlush(Env env, @TempDir Path dir) throws Exception {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        world.loadAround(3, 3, 2);
        world.instance.setBlock(center(3), Y, center(3), Block.TORCH);

        try (LogCapture logs = LogCapture.of(SynxRelight.class)) {
            final CompletableFuture<?> drained = world.relight.drain(Duration.ZERO, true);
            assertTrue(drained.isDone(), "no budget: done at once");
            assertTrue(logs.messagesAt(Level.WARN).stream().anyMatch(line -> line.contains(" 9 targets")),
                    logs.messagesAt(Level.WARN).toString());
        }
        world.store.finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);

        final RelightTestMap map = RelightTestMap.get();
        final OverlayStore reopened = OverlayStore.open(dir, map.manifest, map.settings(), new OverlayOptions(16, 10));
        final OverlayHit hit = reopened.find(3, 3);
        assertNotNull(hit, "3,3 is written by the final flush");
        final byte[] record = hit.record().packet().toArray(ValueLayout.JAVA_BYTE);
        final ChunkDataPacket written = SynxChunkPacket.decode(record, RelightTestMap.REGISTRIES);
        final ChunkDataPacket baked = SynxChunkPacket.decode(map.packet(3, 3), RelightTestMap.REGISTRIES);
        assertTrue(RelightWorld.sameLight(baked.lightData(), written.lightData()), "the light of the record is the baked light");
        final SynxPacketDecoder.Sections sections = SynxPacketDecoder.sections(hit.record().packet(),
                RelightTestMap.REGISTRIES, RelightWorld.SECTIONS);
        assertEquals(Block.TORCH.stateId(), sections.blocks()[Y >> 4].get(8, Y & 15, 8), "the torch is in the record");
    }
}
