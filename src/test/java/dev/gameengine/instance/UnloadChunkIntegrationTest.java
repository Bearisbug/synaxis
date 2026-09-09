package dev.gameengine.instance;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Entity;
import dev.gameengine.entity.EntityType;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class UnloadChunkIntegrationTest {

    // Instance#unloadChunk documents that everything but players is removed
    @Test
    public void unloadingChunkRemovesEntitiesButKeepsPlayers(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick();

        instance.unloadChunk(0, 0);

        assertFalse(player.isRemoved(), "unloadChunk disconnected a player standing in the chunk");
        assertTrue(entity.isRemoved(), "unloadChunk left a non-player entity behind");
    }
}
