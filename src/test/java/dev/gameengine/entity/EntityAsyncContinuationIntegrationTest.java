package dev.gameengine.entity;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.instance.Chunk;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityAsyncContinuationIntegrationTest {

    private static final class ProbeEntity extends Entity {
        final AtomicBoolean chunkRefreshed = new AtomicBoolean();
        final AtomicBoolean ownedOnChunkRefresh = new AtomicBoolean();

        ProbeEntity() {
            super(EntityType.ZOMBIE);
        }

        @Override
        protected void refreshCurrentChunk(Chunk currentChunk) {
            this.chunkRefreshed.set(true);
            this.ownedOnChunkRefresh.set(acquirable().isOwned());
            super.refreshCurrentChunk(currentChunk);
        }
    }

    @Test
    public void teleportContinuationOwnsTheEntity(Env env) {
        var instance = env.createFlatInstance();
        var entity = new ProbeEntity();
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick(); // Let the dispatcher assign a tick thread
        assertNotNull(entity.acquirable().assignedThread());

        entity.chunkRefreshed.set(false);
        // The target chunk is not loaded, so the continuation runs on the chunk loading thread
        entity.teleport(new Pos(4000, 42, 4000)).join();
        assertTrue(entity.chunkRefreshed.get(), "the teleport did not cross a chunk");
        assertTrue(entity.ownedOnChunkRefresh.get(),
                "teleport continuation mutated the entity without owning it");
    }

    @Test
    public void instanceChangeContinuationOwnsTheEntity(Env env) {
        var origin = env.createFlatInstance();
        var target = env.createFlatInstance();
        var entity = new ProbeEntity();
        entity.setInstance(origin, new Pos(0, 42, 0)).join();
        env.tick(); // Let the dispatcher assign a tick thread
        assertNotNull(entity.acquirable().assignedThread());

        entity.chunkRefreshed.set(false);
        entity.setInstance(target, new Pos(4000, 42, 4000)).join();
        assertTrue(entity.chunkRefreshed.get(), "the entity was not moved to the new instance");
        assertTrue(entity.ownedOnChunkRefresh.get(),
                "setInstance continuation mutated the entity without owning it");
    }
}
