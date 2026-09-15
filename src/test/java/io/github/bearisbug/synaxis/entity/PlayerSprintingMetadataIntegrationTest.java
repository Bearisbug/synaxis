package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEntityActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInputPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class PlayerSprintingMetadataIntegrationTest {

    @Test
    public void sprintingMetadata(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        player.addPacketToQueue(new ClientInputPacket(true, false, false, false, false, false, true));
        player.addPacketToQueue(new ClientEntityActionPacket(
                player.getEntityId(),
                ClientEntityActionPacket.Action.START_SPRINTING,
                0
        ));

        var tracker = connection.trackIncoming(EntityMetaDataPacket.class);
        player.interpretPacketQueue();

        var packets = tracker.collect();
        assertEquals(1, packets.size(), "Expected single packet, got multiple");
    }

}
