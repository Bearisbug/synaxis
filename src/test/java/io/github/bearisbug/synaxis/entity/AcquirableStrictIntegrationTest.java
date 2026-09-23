package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.thread.AcquirableOwnershipException;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
public class AcquirableStrictIntegrationTest {

    @Test
    public void foreignThreadMutationThrows(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick(); // Let the dispatcher assign a tick thread

        assertInstanceOf(AcquirableOwnershipException.class, run(() -> zombie.setVelocity(new Vec(1, 0, 0))));
        assertInstanceOf(AcquirableOwnershipException.class, run(() -> zombie.setHealth(5f)));
        assertInstanceOf(AcquirableOwnershipException.class, run(zombie::remove));
    }

    @Test
    public void acquiredMutationIsAllowed(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick();

        assertDoesNotThrow(() -> run(() -> zombie.acquirable().sync(_ -> zombie.setVelocity(new Vec(1, 0, 0)))));
    }

    @Test
    public void foreignThreadInstanceChangeAndTeleportThrow(Env env) {
        // REQ-PROD-019: both entry points of an entity and of a player assert the caller owns it
        var instance = env.createFlatInstance();
        var target = env.createFlatInstance();
        var zombie = new Entity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        env.tick();

        assertInstanceOf(AcquirableOwnershipException.class, run(() -> zombie.teleport(new Pos(1, 42, 1)).join()));
        assertInstanceOf(AcquirableOwnershipException.class, run(() -> zombie.setInstance(target, new Pos(0, 42, 0)).join()));
        assertInstanceOf(AcquirableOwnershipException.class, run(() -> player.teleport(new Pos(1, 42, 1)).join()));
        assertInstanceOf(AcquirableOwnershipException.class, run(() -> player.setInstance(target, new Pos(0, 42, 0)).join()));
        assertSame(instance, zombie.getInstance());
        assertSame(instance, player.getInstance());
    }

    @Test
    public void unassignedEntityCanBeSpawnedFromAnotherThread(Env env) {
        // Nothing ticks an entity before its first tick, so the thread that built it is not the only
        // one allowed to place it: a player is built on its connection and spawned by the scheduler
        var instance = env.createFlatInstance();
        var zombie = new Entity(EntityType.ZOMBIE);
        assertNull(run(() -> zombie.setInstance(instance, new Pos(0, 42, 0)).join()));
        assertSame(instance, zombie.getInstance());
    }

    @Test
    public void ownedPlayerChangesInstanceAcrossUnloadedChunks(Env env) {
        // The spawn half of Player#setInstance runs on the scheduler thread once chunks had to load,
        // and a player that already ticks is not owned there
        var instance = env.createFlatInstance();
        var target = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        env.tick();
        assertNotNull(player.acquirable().assignedThread());

        assertDoesNotThrow(() -> player.acquirable().applySync(p -> p.setInstance(target, new Pos(4000, 42, 4000))).join());
        assertSame(target, player.getInstance());
    }

    private static Throwable run(Runnable runnable) {
        var result = new CompletableFuture<Throwable>();
        Thread.startVirtualThread(() -> {
            try {
                runnable.run();
                result.complete(null);
            } catch (Throwable t) {
                result.complete(t);
            }
        });
        return assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
    }
}
