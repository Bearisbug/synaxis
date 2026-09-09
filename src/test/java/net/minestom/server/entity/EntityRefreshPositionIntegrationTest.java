package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
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
}
