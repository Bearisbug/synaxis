package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

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
}
