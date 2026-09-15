package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientStatusPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@EnvTest
public class PlayerRespawnChunkIntegrationTest {

    @Test
    public void testChunkUnloadsOnRespawn(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));
        player.teleport(new Pos(32, 40, 32)).join();

        var unloadChunkTracker = connection.trackIncoming(UnloadChunkPacket.class);
        player.setHealth(0);
        player.respawn();
        // Since client unloads the chunks, we shouldn't receive any unload packets
        unloadChunkTracker.assertCount(0);
    }

    @Test
    public void testChunkReloadCount(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        var loadChunkTracker = connection.trackIncoming(ChunkDataPacket.class);
        player.setHealth(0);
        player.respawn();
        // Player should have all their chunks reloaded
        int chunkLoads = ChunkRange.chunksCount(player.effectiveViewDistance());
        loadChunkTracker.assertCount(chunkLoads);
    }

    @Test
    public void testPlayerTryRespawn(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        var loadChunkTracker = connection.trackIncoming(ChunkDataPacket.class);
        player.setHealth(0);
        player.addPacketToQueue(new ClientStatusPacket(ClientStatusPacket.Action.PERFORM_RESPAWN));
        player.interpretPacketQueue();
        List<ChunkDataPacket> dataPacketList = loadChunkTracker.collect();
        Set<ChunkDataPacket> duplicateCheck = new HashSet<>();
        int chunkLoads = ChunkRange.chunksCount(player.effectiveViewDistance());
        loadChunkTracker.assertCount(chunkLoads);
        for (ChunkDataPacket packet : dataPacketList) {
            assertFalse(duplicateCheck.contains(packet));
            duplicateCheck.add(packet);
            assertTrue(Math.abs(packet.chunkX()) <= player.effectiveViewDistance() && Math.abs(packet.chunkZ()) <= player.effectiveViewDistance());
        }
    }
}
