package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
public class EntityInstanceSwitchDuringSpawnIntegrationTest {

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
    public void abandonedInstanceMustNotBeJoined(Env env) {
        var gate = new CountDownLatch(1);
        var abandoned = env.createFlatInstance(new GatedChunkLoader(gate));
        var target = env.createFlatInstance();
        var entity = new Entity(EntityType.ZOMBIE);

        var pending = entity.setInstance(abandoned, new Pos(2000, 42, 2000));
        entity.setInstance(target, new Pos(0, 42, 0)).join(); // The first spawn chunk is still loading
        gate.countDown();
        pending.join();

        assertSame(target, entity.getInstance());
        assertNull(abandoned.getEntityTracker().getEntityById(entity.getEntityId()),
                "the entity leaked into the instance it left while its spawn chunk was loading");
        assertFalse(abandoned.getEntities().contains(entity));
        assertSame(entity, target.getEntityTracker().getEntityById(entity.getEntityId()));

        entity.remove();
        assertNull(target.getEntityTracker().getEntityById(entity.getEntityId()));
        assertNull(abandoned.getEntityTracker().getEntityById(entity.getEntityId()),
                "the abandoned instance still holds the removed entity");
    }
}
