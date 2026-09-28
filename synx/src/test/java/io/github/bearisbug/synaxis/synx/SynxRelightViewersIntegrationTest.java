package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateLightPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §19 {@code REQ-M3-012}, the multi-viewer scenarios of the runtime light
 * ({@code ADR-M3-002}): real players on recording connections stand for the clients, their chunk ring
 * and the tick each chunk was delivered in set by where they spawn, their view distance cap and the
 * ticks the test runs. The recomputations wait for the test ({@link RelightWorld.ManualExecutor}), so
 * that a scenario knows the tick its result is installed in.
 */
@EnvTest
public class SynxRelightViewersIntegrationTest {
    private static final int Y = RelightTestMap.FLOOR;
    /** The center chunk C = (3,3) and the target T = (3,4) south of it. */
    private static final int C = 3;
    /** In C next to T: the south edge of C, x 56. */
    private static final int TORCH_X = 56, TORCH_Z = 62;

    private static Pos at(int chunkX, int chunkZ) {
        return new Pos(chunkX * 16 + 4.5, Y, chunkZ * 16 + 4.5);
    }

    /** Whether the server's light of a chunk differs from the light it was baked with. */
    private static boolean changedSinceTheBake(RelightWorld world, int chunkX, int chunkZ) {
        final LightData baked = SynxChunkPacket.decode(RelightTestMap.get().packet(chunkX, chunkZ),
                RelightTestMap.REGISTRIES).lightData();
        try {
            RelightWorld.assertLight("baked", baked, world.truth(chunkX, chunkZ), chunkX, chunkZ);
            return false;
        } catch (AssertionError _) {
            return true;
        }
    }

    /** Each light packet of a chunk a viewer got is a full one, with the server's light. */
    private static void assertFullAndTrue(RelightWorld world, RelightWorld.Viewer viewer, int chunkX, int chunkZ, String who) {
        final LightResult truth = world.truth(chunkX, chunkZ);
        for (UpdateLightPacket packet : viewer.lightPackets(chunkX, chunkZ)) {
            assertTrue(RelightWorld.full(packet.lightData()), who + ": a full light packet of " + chunkX + "," + chunkZ);
            RelightWorld.assertLight(who + " light packet of " + chunkX + "," + chunkZ, packet.lightData(), truth, chunkX, chunkZ);
        }
    }

    /** 场景「能自算的观察者不收光照包，不能自算的收一次」. */
    @Test
    public void viewersThatComputeTheLightThemselvesGetNoPacketTheOthersOne(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        final RelightWorld.Viewer a = world.connect(at(C, C), 2);
        final RelightWorld.Viewer b = world.connect(at(C, 7), 2);
        world.ticks(200);
        final RelightWorld.Viewer d = world.connect(at(C, C), 2);
        world.ticks(9);
        a.forget();
        b.forget();
        d.forget();

        world.instance.setBlock(TORCH_X, Y, TORCH_Z, Block.TORCH);
        world.settle();
        world.ticks(200);

        assertEquals(1, a.blockChanges(TORCH_X, Y, TORCH_Z), "A got the block change");
        assertEquals(List.of(), a.connection().packets(UpdateLightPacket.class), "A computes the light itself");
        assertEquals(0, b.blockChanges(TORCH_X, Y, TORCH_Z), "B does not hold C");
        assertEquals(1, d.blockChanges(TORCH_X, Y, TORCH_Z), "D got the block change");
        for (int x = C - 1; x <= C + 1; x++) {
            for (int z = C - 1; z <= C + 1; z++) {
                final boolean changed = changedSinceTheBake(world, x, z);
                if (z == C + 1) {
                    assertEquals(changed ? 1 : 0, b.lightPackets(x, z).size(), "B, " + x + "," + z + " changed=" + changed);
                } else {
                    assertEquals(0, b.lightPackets(x, z).size(), "B does not hold " + x + "," + z);
                }
                assertEquals(1, d.lightPackets(x, z).size(), "D, delivered 10 ticks before, must get " + x + "," + z);
                assertFullAndTrue(world, b, x, z, "B");
                assertFullAndTrue(world, d, x, z, "D");
            }
        }
        assertTrue(changedSinceTheBake(world, C, C + 1), "T is lit by the torch");
    }

    /** 场景「重算排队时才收到区块的玩家收一次补发」. */
    @Test
    public void aPlayerSentTheChunksWhileTheirRecomputationWaitsGetsOneResend(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        world.loadAround(C, C, 2);
        world.instance.setBlock(TORCH_X, Y, TORCH_Z, Block.TORCH);

        final RelightWorld.Viewer e = world.connect(at(C, C), 2);
        world.settle();
        world.ticks(10);

        for (int x = C - 1; x <= C + 1; x++) {
            for (int z = C - 1; z <= C + 1; z++) {
                final boolean changed = changedSinceTheBake(world, x, z);
                assertEquals(changed ? 1 : 0, e.lightPackets(x, z).size(), "E, " + x + "," + z + " changed=" + changed);
                assertFullAndTrue(world, e, x, z, "E");
            }
        }
        assertEquals(1, e.lightPackets(C, C).size(), "C");
        assertEquals(1, e.lightPackets(C, C + 1).size(), "T");
    }

    /**
     * 场景「目标的 3×3 里有观察者没收到的区块时补发」, with its counter proof in {@code docs/TEST.md}: the
     * judgement on the center and the target alone sends A nothing three times.
     */
    @Test
    public void aTargetWhoseThreeByThreeTheViewerDoesNotHoldIsResent(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        // A at 0,3 with the smallest ring, 3: x -3 to 3, z 0 to 6; C = 3,3 and T = 3,4 in it, x 4 not
        final RelightWorld.Viewer a = world.connect(at(0, C), 2);
        world.ticks(200);
        // U = 4,4: glowstone on its west edge, which lights 3,3 to 3,5, which A holds
        world.instance.setBlock(64, Y, 72, Block.GLOWSTONE);
        world.settle();
        world.ticks(110);

        final int[][] changes = {{63, 63}, {63, 63}, {48, 48}};
        final Block[] blocks = {Block.TORCH, Block.AIR, Block.STONE};
        for (int round = 0; round < 3; round++) {
            a.forget();
            world.instance.setBlock(changes[round][0], Y, changes[round][1], blocks[round]);
            world.settle();
            world.ticks(110);
            final String what = "round " + (round + 1) + " (" + blocks[round].name() + ")";
            assertEquals(1, a.blockChanges(changes[round][0], Y, changes[round][1]), what + ": A got the block change");
            for (int z = C - 1; z <= C + 1; z++) {
                assertEquals(1, a.lightPackets(C, z).size(), what + ": the 3×3 of " + C + "," + z + " holds x 4, A must get it");
                assertEquals(0, a.lightPackets(C - 1, z).size(), what + ": A computes " + (C - 1) + "," + z + " itself");
                assertFullAndTrue(world, a, C, z, "A " + what);
            }
        }
    }

    /**
     * 场景「收到光照包后 100 tick 内附近的改动改为补发」, with its counter proof in {@code docs/TEST.md}:
     * without the refresh of the delivery tick, D gets nothing the second time.
     */
    @Test
    public void aLightPacketTurnsTheChangesOfThe100TicksAfterItIntoResends(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        final RelightWorld.Viewer d = world.connect(at(C, C), 2);
        final long start = world.instance.getWorldAge();

        final long[] ticks = {10, 105, 300};
        final Block[] blocks = {Block.TORCH, Block.AIR, Block.TORCH};
        final int[] expected = {1, 1, 0};
        for (int round = 0; round < 3; round++) {
            final long tick = start + ticks[round];
            world.tickUntil(() -> world.instance.getWorldAge() >= tick);
            d.forget();
            world.instance.setBlock(TORCH_X, Y, TORCH_Z, blocks[round]);
            world.settle();
            world.ticks(2);
            for (int x = C - 1; x <= C + 1; x++) {
                for (int z = C - 1; z <= C + 1; z++) {
                    assertEquals(expected[round], d.lightPackets(x, z).size(),
                            "round " + (round + 1) + " at tick " + ticks[round] + ": " + x + "," + z);
                    assertFullAndTrue(world, d, x, z, "D round " + (round + 1));
                }
            }
        }
    }

    /**
     * 场景「退路开关打开时全部已交付观察者收到变了的 section」: A, which computes the light itself,
     * gets one packet of the changed sections of each target whose light changed; B and D get their
     * full packets as without the fallback and nothing more. The four torches record what the light
     * packets weigh on the wire (§15 光照下行), framed with the threshold 256 at level 6.
     */
    @Test
    public void theFallbackSendsTheChangedSectionsToEveryViewer(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, RelightTestMap.BOUNDS, RelightWorld.options(true),
                new OverlayOptions(16_384, 10), true);
        final RelightWorld.Viewer a = world.connect(at(C, C), 2);
        final RelightWorld.Viewer b = world.connect(at(C, 7), 2);
        world.ticks(200);
        final RelightWorld.Viewer d = world.connect(at(C, C), 2);
        world.ticks(9);

        final int[][] torches = {{TORCH_X, Y, TORCH_Z}, {56, Y, 56}, {63, Y, 48}, {52, 30, 52}};
        final String[] names = {"edge", "center", "corner", "cave"};
        for (int round = 0; round < torches.length; round++) {
            // Apart by more than 100 ticks: a light packet of one round does not make the next one a resend
            if (round > 0) world.ticks(110);
            a.forget();
            b.forget();
            d.forget();
            world.instance.setBlock(torches[round][0], torches[round][1], torches[round][2], Block.TORCH);
            world.settle();
            world.ticks(2);
            long partialBytes = 0, fullBytes = 0;
            int partial = 0, full = 0;
            for (RelightWorld.Viewer viewer : List.of(a, b, d)) {
                for (UpdateLightPacket packet : viewer.connection().packets(UpdateLightPacket.class)) {
                    final long bytes = framed(packet);
                    if (RelightWorld.full(packet.lightData())) {
                        full++;
                        fullBytes += bytes;
                    } else {
                        partial++;
                        partialBytes += bytes;
                    }
                }
            }
            System.out.printf("relight fallback %s torch at %d %d %d: %d partial packets %d B, %d full packets %d B%n",
                    names[round], torches[round][0], torches[round][1], torches[round][2], partial, partialBytes, full,
                    fullBytes);
            if (round > 0) continue;
            for (int x = C - 1; x <= C + 1; x++) {
                for (int z = C - 1; z <= C + 1; z++) {
                    final boolean changed = changedSinceTheBake(world, x, z);
                    final List<UpdateLightPacket> sentToA = a.lightPackets(x, z);
                    assertEquals(changed ? 1 : 0, sentToA.size(), "A, " + x + "," + z + " changed=" + changed);
                    for (UpdateLightPacket packet : sentToA) {
                        assertTrue(!RelightWorld.full(packet.lightData()), "only the changed sections of " + x + "," + z);
                        assertChangedSectionsTrue(world, packet.lightData(), x, z);
                    }
                    if (z == C + 1) assertEquals(changed ? 1 : 0, b.lightPackets(x, z).size(), "B, " + x + "," + z);
                    assertEquals(1, d.lightPackets(x, z).size(), "D, " + x + "," + z);
                    assertFullAndTrue(world, b, x, z, "B");
                    assertFullAndTrue(world, d, x, z, "D");
                }
            }
        }
    }

    /**
     * Two changes of one tick in neighbouring centers, 3,3 and 4,3, both computed before either is
     * installed: the six targets of both 3×3s take the new light once. With the fallback A, which
     * computes the light itself, gets at most one packet of a target, and 4,3 goes up by one
     * {@code generation} for its block and one for its light.
     */
    @Test
    public void neighbouringChangesOfOneTickInstallEachTargetOnce(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, RelightTestMap.BOUNDS, RelightWorld.options(true),
                new OverlayOptions(16_384, 10), true);
        // The ring of A, x and z 0 to 6, holds the 5×5 of both centers
        final RelightWorld.Viewer a = world.connect(at(C, C), 2);
        world.ticks(200);
        a.forget();
        final long generation = world.chunk(C + 1, C).generation();

        world.instance.setBlock(56, Y, 56, Block.TORCH);
        world.instance.setBlock(72, Y, 56, Block.TORCH);
        world.settle();
        world.ticks(2);

        for (int x = C - 1; x <= C + 2; x++) {
            for (int z = C - 1; z <= C + 1; z++) {
                final boolean changed = changedSinceTheBake(world, x, z);
                final List<UpdateLightPacket> sentToA = a.lightPackets(x, z);
                assertEquals(changed ? 1 : 0, sentToA.size(), "A, " + x + "," + z + " changed=" + changed);
                for (UpdateLightPacket packet : sentToA) {
                    assertTrue(!RelightWorld.full(packet.lightData()), "only the changed sections of " + x + "," + z);
                    assertChangedSectionsTrue(world, packet.lightData(), x, z);
                }
            }
        }
        assertTrue(changedSinceTheBake(world, C + 1, C), "4,3 is lit by the torches");
        assertEquals(generation + 2, world.chunk(C + 1, C).generation(), "4,3: its torch and one install of its light");
        world.assertLight(C - 1, C - 1, C + 2, C + 1);
    }

    /** The sections a partial light packet carries hold the server's light; the others are left out of both masks. */
    private static void assertChangedSectionsTrue(RelightWorld world, LightData light, int chunkX, int chunkZ) {
        final LightResult truth = world.truth(chunkX, chunkZ);
        for (int section = 0; section < RelightWorld.SECTIONS; section++) {
            final byte[] sky = RelightWorld.array(light.skyMask(), light.emptySkyMask(), light.skyLight(), section);
            if (sky != null) assertTrue(Arrays.equals(norm(truth.sky(chunkX, section, chunkZ)), norm(sky)),
                    "sky section " + section + " of " + chunkX + "," + chunkZ);
            final byte[] block = RelightWorld.array(light.blockMask(), light.emptyBlockMask(), light.blockLight(), section);
            if (block != null) assertTrue(Arrays.equals(norm(truth.block(chunkX, section, chunkZ)), norm(block)),
                    "block section " + section + " of " + chunkX + "," + chunkZ);
        }
    }

    private static byte[] norm(byte[] array) {
        return array.length == 0 ? new byte[2048] : array;
    }

    /** The bytes a packet takes on a compressed connection: threshold 256, the engine's level 6. */
    private static long framed(UpdateLightPacket packet) {
        final NetworkBuffer body = Objects.requireNonNull(new CachedPacket(packet).body(ConnectionState.PLAY,
                RelightTestMap.REGISTRIES, 256));
        return body.capacity();
    }
}
