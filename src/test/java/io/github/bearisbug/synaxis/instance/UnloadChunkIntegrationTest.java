package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
