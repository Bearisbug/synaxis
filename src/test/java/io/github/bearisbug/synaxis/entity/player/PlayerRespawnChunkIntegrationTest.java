package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientStatusPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    public void respawnDoesNotWaitForTheRespawnChunk(Env env) throws InterruptedException {
        final Pos respawnPosition = new Pos(5000, 42, 5000);
        final CountDownLatch release = new CountDownLatch(1);
        // Only the respawn area is held back, the player still spawns around the origin
        final ChunkLoader blockingLoader = new ChunkLoader() {
            @Override
            public Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
                if (chunkX >= 100) {
                    try {
                        release.await();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
                return null;
            }

            @Override
            public void saveChunk(Chunk chunk) {
            }

            @Override
            public boolean supportsParallelLoading() {
                return true;
            }
        };

        env.process().connection().setPlayerProvider(RespawnProbePlayer::new);
        var instance = env.createFlatInstance(blockingLoader);
        var player = (RespawnProbePlayer) env.createConnection().connect(instance, new Pos(0, 40, 0));
        player.setRespawnPoint(respawnPosition);

        player.setHealth(0);
        // Releases the loader if respawn() blocks, so a regression fails the assertion instead of hanging.
        // Started right before respawn(): its 3 s must not include building the instance and connecting
        final Thread watchdog = Thread.ofPlatform().daemon().name("respawn-watchdog").start(() -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        player.respawn();
        final boolean returnedWhileLoading = release.getCount() == 1;
        release.countDown();
        watchdog.interrupt();
        final boolean teleported = player.respawnTeleport.await(10, TimeUnit.SECONDS);

        assertTrue(returnedWhileLoading,
                "respawn() waited for the respawn chunk, on a tick thread that blocks the whole server");
        assertTrue(teleported, "the respawn teleport must still happen once the chunk is loaded");
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

    private static final class RespawnProbePlayer extends Player {
        private final CountDownLatch respawnTeleport = new CountDownLatch(1);

        RespawnProbePlayer(PlayerConnection playerConnection, GameProfile gameProfile) {
            super(playerConnection, gameProfile);
        }

        @Override
        protected void refreshAfterTeleport() {
            super.refreshAfterTeleport();
            respawnTeleport.countDown();
        }
    }
}
