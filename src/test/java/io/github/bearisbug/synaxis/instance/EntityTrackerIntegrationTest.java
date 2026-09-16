package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
public class EntityTrackerIntegrationTest {

    @Test
    public void maxDistance(Env env) {
        final Instance instance = env.createFlatInstance();
        final Pos spawnPos = new Pos(0, 41, 0);
        final int viewDistanceInChunks = env.process().config().entityViewDistance();

        final Player viewer = env.createPlayer(instance, spawnPos);
        final AtomicInteger viewersCount = new AtomicInteger();
        final Entity entity = new Entity(EntityType.ZOMBIE) {
            @Override
            public void updateNewViewer(Player player) {
                viewersCount.incrementAndGet();
            }

            @Override
            public void updateOldViewer(Player player) {
                viewersCount.decrementAndGet();
            }
        };
        entity.setInstance(instance, spawnPos).join();
        assertEquals(1, viewersCount.get());
        viewer.teleport(new Pos(viewDistanceInChunks * 16 + 15, 41, 0)).join(); // viewer at max chunk range
        assertEquals(1, viewersCount.get());
        viewer.teleport(new Pos(viewDistanceInChunks * 16 + 16, 41, 0)).join(); // viewer outside of chunk range
        assertEquals(0, viewersCount.get());
        viewer.teleport(new Pos(viewDistanceInChunks * 16 + 15, 41, 0)).join(); // viewer back to max chunk range
        assertEquals(1, viewersCount.get());
    }

    @Test
    public void cornerInstanceSwap(Env env) {
        final Instance instance = env.createFlatInstance();
        final Instance anotherInstance = env.createFlatInstance();
        final Pos spawnPos = new Pos(0, 41, 0);
        final int viewDistanceInChunks = env.process().config().entityViewDistance();

        final Player viewer = env.createPlayer(instance, spawnPos);
        final AtomicInteger viewersCount = new AtomicInteger();
        final Entity entity = new Entity(EntityType.ZOMBIE) {
            @Override
            public void updateNewViewer(Player player) {
                viewersCount.incrementAndGet();
            }

            @Override
            public void updateOldViewer(Player player) {
                viewersCount.decrementAndGet();
            }
        };
        entity.setInstance(instance, spawnPos).join();
        assertEquals(1, viewersCount.get());
        viewer.teleport(new Pos(viewDistanceInChunks * 16 + 15, 41, 0)).join(); // viewer at max chunk range
        assertEquals(1, viewersCount.get());
        viewer.setInstance(anotherInstance, spawnPos).join(); // viewer swapped instance
        assertEquals(0, viewersCount.get());
        viewer.setInstance(instance, spawnPos).join(); // viewer back to spawn
        assertEquals(1, viewersCount.get());
    }

    @Test
    public void viewable(Env env) {
        final Instance instance = env.createFlatInstance();
        final Pos spawnPos = new Pos(0, 41, 0);
        var viewable = instance.getEntityTracker().viewable(spawnPos.chunkX(), spawnPos.chunkZ());
        assertEquals(0, viewable.getViewers().size());

        final Player player = env.createPlayer(instance, spawnPos);
        assertEquals(1, viewable.getViewers().size());
        assertSame(viewable, instance.getEntityTracker().viewable(spawnPos.chunkX(), spawnPos.chunkZ()));

        player.teleport(new Pos(10_000, 41, 0)).join();
        assertEquals(0, viewable.getViewers().size());

        player.teleport(spawnPos).join();
        assertEquals(1, viewable.getViewers().size());
    }
}
