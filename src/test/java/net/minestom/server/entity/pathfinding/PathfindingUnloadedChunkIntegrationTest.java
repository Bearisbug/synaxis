package net.minestom.server.entity.pathfinding;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@EnvTest
public class PathfindingUnloadedChunkIntegrationTest {

    @Test
    public void pathingNearUnloadedChunkDoesNotThrow(Env env) {
        var instance = env.createFlatInstance();
        instance.enableAutoChunkLoad(false);
        instance.loadChunk(0, 0).join(); // Only a single chunk is loaded

        var zombie = new LivingEntity(EntityType.ZOMBIE);
        // Right on the chunk border: the A* expansion immediately steps into the unloaded chunk
        zombie.setInstance(instance, new Pos(15.5, 40, 15.5)).join();

        var navigator = new Navigator(zombie);
        assertDoesNotThrow(() -> navigator.setPathTo(new Pos(1.5, 40, 1.5)));
    }
}
