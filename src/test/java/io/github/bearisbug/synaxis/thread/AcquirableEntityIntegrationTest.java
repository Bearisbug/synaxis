package io.github.bearisbug.synaxis.thread;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;

@EnvTest
public class AcquirableEntityIntegrationTest {

    @Test
    public void instanceSet(Env env) throws InterruptedException {
        var zombie = new Entity(EntityType.ZOMBIE);
        CountDownLatch latch = new CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            assertFalse(zombie.acquirable().isOwned());
            assertFalse(zombie.acquirable().isLocal());
            //assertThrows(AcquirableOwnershipException.class, () -> zombie.setInstance(instance, new Pos(1, 41, 1)).join());
            latch.countDown();
        });
        latch.await();
    }
}
