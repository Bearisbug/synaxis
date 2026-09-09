package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.EventFilter;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityRemoveDuringSpawnIntegrationTest {

    private record GatedChunkLoader(CountDownLatch gate) implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null; // Let the generator take over
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }

    @Test
    public void removedEntityMustNotBeRegistered(Env env) {
        var gate = new CountDownLatch(1);
        var instance = env.createFlatInstance(new GatedChunkLoader(gate));
        var entity = new Entity(EntityType.ZOMBIE);
        var spawns = env.trackEvent(EntitySpawnEvent.class, EventFilter.ENTITY, entity);

        var future = entity.setInstance(instance, new Pos(2000, 42, 2000));
        entity.remove(); // The continuation has not run yet
        gate.countDown();
        future.join();

        assertTrue(entity.isRemoved());
        assertNull(instance.getEntityTracker().getEntityById(entity.getEntityId()),
                "removed entity leaked into the tracker");
        assertFalse(instance.getEntities().contains(entity));
        assertNull(entity.getInstance(), "removed entity was re-attached to the instance");
        spawns.assertEmpty();
    }
}
