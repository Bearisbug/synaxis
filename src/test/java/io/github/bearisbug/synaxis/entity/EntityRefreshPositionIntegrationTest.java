package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionAndRotationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityTeleportPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class EntityRefreshPositionIntegrationTest {

    @Test
    public void movingBackToTheLastSyncedPositionStillUpdatesTheEntity(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 40, 0)).join();

        // sendPackets = false, so the entity moves without lastSyncedPosition following it
        entity.refreshPosition(new Pos(1, 40, 0), false, false);
        assertEquals(new Pos(1, 40, 0), entity.getPosition());

        entity.refreshPosition(new Pos(0, 40, 0), false, false);
        assertEquals(new Pos(0, 40, 0), entity.getPosition(),
                "the entity stayed at the old position because the guard compared the synced position");
    }

    @Test
    public void aMoveOfExactlyEightBlocksIsSentAsATeleport(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0.5, 40, 0.5));
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0.5, 40, 0.5)).join();

        // 8 blocks are 32768 in 1/4096 of a block, one more than a short holds: as a relative move the
        // client reads -32768 and puts the entity 8 blocks the other way
        var relative = connection.trackIncoming(EntityPositionAndRotationPacket.class);
        var teleports = connection.trackIncoming(EntityTeleportPacket.class);
        entity.moveTo(new Pos(8.5, 40, 0.5));

        relative.assertNoneMatch(packet -> packet.entityId() == entity.getEntityId());
        teleports.assertSingle(packet -> assertEquals(entity.getEntityId(), packet.entityId()));
    }
}
