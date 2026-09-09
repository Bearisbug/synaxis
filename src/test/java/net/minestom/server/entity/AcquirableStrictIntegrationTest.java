package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.thread.AcquirableOwnershipException;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
