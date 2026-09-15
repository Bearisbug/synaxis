package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // Removing the entities takes their tick thread, and a tick thread takes the instance monitor
    // (setBlock is synchronized): the monitor must not be held while waiting for a tick thread,
    // or the two lock orders deadlock the server.
    @Test
    public void unloadingChunkReleasesTheInstanceMonitorBeforeRemovingEntities(Env env) throws InterruptedException {
        assertMonitorFreeWhileRemoving(env, instance -> instance.unloadChunk(0, 0));
    }

    @Test
    public void unregisteringInstanceReleasesTheInstanceMonitorBeforeRemovingEntities(Env env) throws InterruptedException {
        assertMonitorFreeWhileRemoving(env, instance -> env.process().instance().unregisterInstance(instance));
    }

    private static void assertMonitorFreeWhileRemoving(Env env, Consumer<Instance> unload) throws InterruptedException {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick(); // Hands the entity over to a tick thread

        assertNotNull(entity.acquirable().assignedThread(), "the entity was never dispatched to a tick thread");
        // Owning the entity blocks its removal, just like a tick in progress would
        var acquired = entity.acquirable().lock();
        Thread unloader = Thread.ofPlatform().name("chunk-unloader").start(() -> unload.accept(instance));
        boolean monitorFree;
        try {
            awaitBlocked(unloader);
            CountDownLatch entered = new CountDownLatch(1);
            Thread.ofPlatform().name("monitor-probe").start(() -> {
                synchronized (instance) {
                    entered.countDown();
                }
            });
            monitorFree = entered.await(5, TimeUnit.SECONDS);
        } finally {
            acquired.unlock();
            unloader.join();
        }
        assertTrue(monitorFree, "the instance monitor was held while waiting for a tick thread");
        assertTrue(entity.isRemoved(), "the entity in the unloaded chunk was left behind");
    }

    private static void awaitBlocked(Thread thread) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final Thread.State state = thread.getState();
            if (state != Thread.State.NEW && state != Thread.State.RUNNABLE) return;
            Thread.onSpinWait();
        }
    }
}
