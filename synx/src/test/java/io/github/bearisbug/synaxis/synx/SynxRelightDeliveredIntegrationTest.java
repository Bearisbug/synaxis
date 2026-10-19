package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivered and light-stale tables of {@code docs/DESIGN-M3.md} {@code API-M3-005} 已交付表与光照待补
 * once a player is gone: the engine reports the unloads of its whole ticket ring as it is removed, so no
 * table keeps it, also after a walk that left the ring centred elsewhere than the player.
 */
@EnvTest
class SynxRelightDeliveredIntegrationTest {
    private static final int Y = RelightTestMap.FLOOR;
    private static final int C = 3;
    /** The ring of a viewer of view distance 2: one chunk more. */
    private static final int RING = 3;

    private static Pos at(int chunkX, int chunkZ) {
        return new Pos(chunkX * 16 + 4.5, Y, chunkZ * 16 + 4.5);
    }

    /** What a client sends, through the packet queue and one tick: it confirms its last teleport, then one position. */
    private static void walk(RelightWorld world, Player player, Pos to) {
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(to.x(), to.y(), to.z()), (byte) 1));
        world.tick();
    }

    /** 场景「来回走过区块边界后断线，已交付表里不留这名玩家」. */
    @Test
    void aPlayerWhoWalkedBackAndForthAndLeftIsInNoTable(Env env, @TempDir Path dir) {
        final RelightWorld world = RelightWorld.create(env, dir, true);
        final RelightWorld.Viewer viewer = world.connect(at(C, C), 2);
        final Player player = viewer.player();
        // East into C + 1: the ring follows. Back into C: the chunk update limiter has C in its history and
        // leaves the ring on C + 1, so its east column C + 4 is one the position's ring does not reach
        walk(world, player, at(C + 1, C));
        world.awaitRing(viewer, C + 1, C, RING);
        walk(world, player, at(C, C));
        world.ticks(5);
        assertTrue(world.chunk(C + 1 + RING, C).delivered().containsKey(player),
                "the ring stayed on C + 1: its east column was delivered");

        player.getPlayerConnection().disconnect();
        world.ticks(5);

        assertFalse(player.isOnline());
        for (int x = RelightTestMap.BOUNDS.minX(); x <= RelightTestMap.BOUNDS.maxX(); x++) {
            for (int z = RelightTestMap.BOUNDS.minZ(); z <= RelightTestMap.BOUNDS.maxZ(); z++) {
                final Chunk chunk = world.instance.getChunk(x, z);
                if (!(chunk instanceof SynxChunk synx)) continue;
                assertFalse(synx.delivered().containsKey(player), x + "," + z + " still has the player as delivered");
                assertFalse(synx.lightStale().containsKey(player), x + "," + z + " still has the player as light stale");
            }
        }
    }
}
