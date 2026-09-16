package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerChunkQueueIntegrationTest {

    @Test
    public void viewDistanceChangesDoNotQueueAChunkTwice(Env env) {
        // The test player sends chunks immediately, the queue under test is the one of a plain player
        env.process().connection().setPlayerProvider(Player::new);
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        var chunks = connection.trackIncoming(ChunkDataPacket.class);
        // A client toggling its view distance, every switch queues the whole ring again
        for (int i = 0; i < 5; i++) {
            player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(2)));
            player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(32)));
        }
        player.interpretPacketQueue();

        // Drain the queue, acknowledging every batch like a client does
        for (int i = 0; i < 60; i++) {
            player.onChunkBatchReceived(64f);
            env.tick();
        }

        Set<Long> sent = new HashSet<>();
        for (ChunkDataPacket packet : chunks.collect()) {
            assertTrue(sent.add(CoordConversion.chunkIndex(packet.chunkX(), packet.chunkZ())),
                    "chunk " + packet.chunkX() + "," + packet.chunkZ() + " was queued more than once");
        }
    }

    @Test
    public void viewDistanceChangesAreAppliedOncePerTick(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        final int rangeBefore = player.effectiveViewDistance();
        var unloaded = connection.trackIncoming(UnloadChunkPacket.class);
        // A client toggling its view distance, every switch used to recompute the whole ring
        for (int i = 0; i < 5; i++) {
            player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(32)));
            player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(2)));
        }
        env.tick();
        env.tick();

        // The last packet of the batch is the one that counts, and it is applied once
        assertEquals(2, player.getSettings().viewDistance());
        final int rangeAfter = player.effectiveViewDistance();
        assertEquals(3, rangeAfter, "the view distance of the last packet must be the one applied");
        assertTrue(rangeAfter < rangeBefore, "the view distance must have shrunk");
        assertEquals(ChunkRange.chunksCount(rangeBefore) - ChunkRange.chunksCount(rangeAfter),
                unloaded.collect().size(), "the chunk ring must be recomputed once per tick");
    }

    @Test
    public void viewDistanceShrinkUnloadsTheRingTheClientHas(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0.5, 40, 0.5));
        final int rangeBefore = player.effectiveViewDistance();

        var unloaded = connection.trackIncoming(UnloadChunkPacket.class);
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        // Shrink the view distance and cross a chunk border in the same tick: the movement happens before the
        // new view distance is applied, so it must move the ring the client actually has, at its own radius
        player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(2)));
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(16.5, 40, 0.5), true, false));
        env.tick();

        final int rangeAfter = player.effectiveViewDistance();
        assertEquals(3, rangeAfter);
        List<Long> unloadedChunks = new ArrayList<>();
        for (UnloadChunkPacket packet : unloaded.collect()) {
            unloadedChunks.add(CoordConversion.chunkIndex(packet.chunkX(), packet.chunkZ()));
        }
        // The ring follows the player at its old radius, then shrinks around the new center: every chunk the
        // client was sent and is now out of range must be unloaded, and no other one may be
        Set<Long> expected = new HashSet<>();
        ChunkRange.chunksInRange(0, 0, rangeBefore, (x, z) -> expected.add(CoordConversion.chunkIndex(x, z)));
        ChunkRange.chunksInRange(1, 0, rangeBefore, (x, z) -> expected.add(CoordConversion.chunkIndex(x, z)));
        ChunkRange.chunksInRange(1, 0, rangeAfter, (x, z) -> expected.remove(CoordConversion.chunkIndex(x, z)));
        assertEquals(expected, new HashSet<>(unloadedChunks),
                "the unloaded chunks must be exactly the ones the client has and no longer needs");
        assertEquals(expected.size(), unloadedChunks.size(), "a chunk must not be unloaded twice");
    }

    // The shrink unloads a chunk on the client, but a chunk already waiting in the send queue used to
    // be sent anyway right after: the client kept it while the server had written it off (REQ-M2-014)
    @Test
    public void shrinkingTheViewDistanceDropsThePendingChunksItUnloaded(Env env) {
        // The test player sends chunks immediately, the queue under test is the one of a plain player
        env.process().connection().setPlayerProvider(Player::new);
        var instance = env.createFlatInstance();
        // Generating up front makes the chunk adder queue the whole ring while the player spawns
        ChunkRange.chunksInRange(0, 0, 5, (chunkX, chunkZ) -> instance.loadChunk(chunkX, chunkZ).join());
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().refreshSettings(ClientSettings.defaultSettings(4)));
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));
        assertEquals(5, player.effectiveViewDistance());

        var chunks = connection.trackIncoming(ChunkDataPacket.class);
        player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(2)));
        env.tick();
        final int rangeAfter = player.effectiveViewDistance();
        assertEquals(3, rangeAfter);

        // Drain the queue, acknowledging every batch like a client does
        for (int i = 0; i < 20; i++) {
            player.onChunkBatchReceived(64f);
            env.tick();
        }

        for (ChunkDataPacket packet : chunks.collect()) {
            final int distance = Math.max(Math.abs(packet.chunkX()), Math.abs(packet.chunkZ()));
            assertTrue(distance <= rangeAfter, "chunk " + packet.chunkX() + "," + packet.chunkZ()
                    + " was sent after the view distance shrank past it");
        }
    }

    // The client keeps its chunk ring around the last center the server sent it, which the chunk update
    // limiter can hold back. Unloading around the player position instead leaves the client holding
    // chunks the server believes it dropped (REQ-M2-014)
    @Test
    public void shrinkingTheViewDistanceUnloadsAroundTheRingTheClientHas(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(8.5, 40, 8.5));
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        // Crossing the border and coming back: the chunk update limiter already remembers chunk 0,0
        // from the spawn and swallows the return, so the ring the client has stays centered on chunk
        // 1,0 while the player stands in chunk 0,0
        crossTo(env, player, 24.5);
        crossTo(env, player, 8.5);
        assertEquals(0, player.getPosition().chunkX(), "the player was supposed to stand back in chunk 0,0");

        final int rangeBefore = player.effectiveViewDistance();
        var unloaded = connection.trackIncoming(UnloadChunkPacket.class);
        player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(2)));
        env.tick();
        final int rangeAfter = player.effectiveViewDistance();
        assertEquals(3, rangeAfter);

        Set<Long> expected = new HashSet<>();
        ChunkRange.chunksInRange(1, 0, rangeBefore, (x, z) -> expected.add(CoordConversion.chunkIndex(x, z)));
        ChunkRange.chunksInRange(1, 0, rangeAfter, (x, z) -> expected.remove(CoordConversion.chunkIndex(x, z)));
        Set<Long> unloadedChunks = new HashSet<>();
        for (UnloadChunkPacket packet : unloaded.collect()) {
            unloadedChunks.add(CoordConversion.chunkIndex(packet.chunkX(), packet.chunkZ()));
        }
        assertEquals(expected, unloadedChunks,
                "the shrink must unload the ring the client holds, not the one around the player");
    }

    private static void crossTo(Env env, Player player, double x) {
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(x, 40, 8.5), true, false));
        env.tick();
    }

    @Test
    public void viewDistanceSentBeforeSpawnBuildsTheFirstRing(Env env) {
        var instance = env.createFlatInstance();
        // A client sends its settings during configuration, when it has no chunk yet: the first ring must be
        // built from them rather than from the server default and resized on the first tick
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().refreshSettings(ClientSettings.defaultSettings(4)));
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        assertEquals(5, player.effectiveViewDistance());
    }
}
