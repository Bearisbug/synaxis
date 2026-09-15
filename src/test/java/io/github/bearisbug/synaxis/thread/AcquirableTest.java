package io.github.bearisbug.synaxis.thread;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AcquirableTest {

    private static void rethrow(Throwable throwable) {
        throw new AssertionError("Dispatcher reported an exception", throwable);
    }

    @Test
    public void assignation() {
        AtomicReference<TickThread> tickThread = new AtomicReference<>();
        Entity entity = new Entity(EntityType.ZOMBIE) {
            @Override
            public void tick(long time) {
                super.tick(time);
                tickThread.set(acquirable().assignedThread());
            }
        };
        Object first = new Object();
        Object second = new Object();

        ThreadDispatcher<Object, Entity> dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), 2, AcquirableTest::rethrow);
        dispatcher.start();
        dispatcher.createPartition(first);
        dispatcher.createPartition(second);

        dispatcher.updateElement(entity, first);
        dispatcher.updateAndAwait(System.nanoTime());
        TickThread firstThread = tickThread.get();
        assertNotNull(firstThread);

        tickThread.set(null);
        dispatcher.updateElement(entity, second);
        dispatcher.updateAndAwait(System.nanoTime());
        TickThread secondThread = tickThread.get();
        assertNotNull(secondThread);

        assertNotEquals(firstThread, secondThread);
    }

    @Test
    @Timeout(30)
    public void trySyncOrdersThroughGlobalLock() throws InterruptedException {
        // Acquiring a foreign tick thread lock must be ordered by the global lock, see AcquirableImpl#enter.
        // Taking the tick thread lock without it inverts the order against sync() and deadlocks the process.
        Entity entity = new Entity(EntityType.ZOMBIE);
        Object partition = new Object();
        ThreadDispatcher<Object, Entity> dispatcher = ThreadDispatcher.singleThread(AcquirableTest::rethrow);
        dispatcher.start();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(entity, partition);
        dispatcher.updateAndAwait(System.nanoTime());
        var acquirable = entity.acquirable();
        assertNotNull(acquirable.assignedThread());

        try {
            AtomicBoolean globalLockHeld = new AtomicBoolean();
            // The global lock is shared by the whole JVM, another test class may hold it for a moment.
            boolean acquired = false;
            for (int i = 0; i < 100 && !acquired; i++) {
                acquired = acquirable.trySync(_ -> globalLockHeld.set(AcquirableImpl.GLOBAL_LOCK.isHeldByCurrentThread()));
            }
            assertTrue(acquired, "trySync never acquired an idle element");
            assertTrue(globalLockHeld.get(), "trySync acquired the tick thread lock without the global lock");
            assertFalse(AcquirableImpl.GLOBAL_LOCK.isHeldByCurrentThread(), "trySync did not release the global lock");

            // While someone else owns the global lock, trySync must give up rather than take the tick
            // thread lock out of order.
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                AcquirableImpl.GLOBAL_LOCK.lock();
                try {
                    locked.countDown();
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    AcquirableImpl.GLOBAL_LOCK.unlock();
                }
            }, "test-global-lock-holder");
            holder.setDaemon(true);
            holder.start();
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS), "the global lock was never taken");
                assertFalse(acquirable.trySync(_ -> fail("trySync ran while the global lock was held elsewhere")),
                        "trySync bypassed the global lock");
            } finally {
                release.countDown();
                holder.join(5_000);
            }
        } finally {
            dispatcher.shutdown();
        }
    }
}
