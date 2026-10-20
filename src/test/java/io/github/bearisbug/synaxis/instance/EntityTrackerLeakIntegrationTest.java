package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class EntityTrackerLeakIntegrationTest {

    @Test
    public void emptyChunkBucketsAreReleased() {
        var tracker = (EntityTrackerImpl) EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        var entry = tracker.targetEntries[EntityTracker.Target.ENTITIES.ordinal()];
        var entity = new Entity(EntityType.ZOMBIE);
        for (int x = 0; x < 100; x++) {
            tracker.register(entity, new Vec(x << 4, 0, 0), EntityTracker.Target.ENTITIES, null);
            tracker.unregister(entity, EntityTracker.Target.ENTITIES, null);
        }
        assertEquals(0, entry.chunkEntities.size(), "empty chunk buckets must be removed");
    }

    @Test
    public void queryingAnEmptyChunkDoesNotCreateABucket() {
        var tracker = (EntityTrackerImpl) EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        var entry = tracker.targetEntries[EntityTracker.Target.ENTITIES.ordinal()];
        for (int x = 0; x < 100; x++) {
            assertEquals(0, tracker.chunkEntities(x, 0, EntityTracker.Target.ENTITIES).size());
        }
        assertEquals(0, entry.chunkEntities.size(), "reading a chunk must not create a bucket");
    }

    @Test
    public void reloadingChunksDoesNotGrowViewers(Env env) {
        var instance = env.createFlatInstance();
        var tracker = (EntityTrackerImpl) instance.getEntityTracker();
        var viewers = tracker.chunkViews;
        for (int i = 0; i < 20; i++) {
            instance.loadChunk(0, 0).join();
            instance.unloadChunk(0, 0);
        }
        assertEquals(0, viewers.size(), "ChunkView must not accumulate across load/unload");
    }

    @Test
    public void reloadingChunksDoesNotGrowChunkEntities(Env env) {
        var instance = env.createFlatInstance();
        var tracker = (EntityTrackerImpl) instance.getEntityTracker();
        for (int i = 0; i < 20; i++) {
            instance.loadChunk(0, 0).join();
            instance.unloadChunk(0, 0);
        }
        for (var entry : tracker.targetEntries) {
            assertEquals(0, entry.chunkEntities.size(), "chunk buckets must not accumulate across load/unload");
        }
    }
}
