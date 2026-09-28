package io.github.bearisbug.synaxis.thread;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ThreadDispatcher<Object, Entity> dispatcher = dispatch(entity);
        dispatcher.updateAndAwait(System.nanoTime());
        var acquirable = entity.acquirable();
        assertNotNull(acquirable.assignedThread());

        try {
            assertTrySyncTakesTheGlobalLock(acquirable);

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
                locked.await(); // Waits out the holders in other test classes like the check above
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

    @Test
    @Timeout(30)
    public void trySyncOnAnIdleElementGivesUpWhileAnotherElementIsSynced() throws InterruptedException {
        // sync() keeps the JVM-wide global lock until its callback returns, whatever element it is on: trySync on
        // an element nobody uses gives up all that time, and the check has to wait it out rather than sample the
        // lock a bounded number of times.
        Entity idle = new Entity(EntityType.ZOMBIE);
        Entity other = new Entity(EntityType.ZOMBIE);
        ThreadDispatcher<Object, Entity> idleDispatcher = dispatch(idle);
        ThreadDispatcher<Object, Entity> otherDispatcher = dispatch(other);
        idleDispatcher.updateAndAwait(System.nanoTime());
        otherDispatcher.updateAndAwait(System.nanoTime());
        CountDownLatch inCallback = new CountDownLatch(1);
        CountDownLatch finishCallback = new CountDownLatch(1);
        Thread syncer = new Thread(() -> other.acquirable().sync(_ -> {
            inCallback.countDown();
            try {
                finishCallback.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }), "test-long-sync");
        Thread main = Thread.currentThread();
        // Ends the callback once the check below waits for the global lock, never before.
        Thread releaser = new Thread(() -> {
            while (!AcquirableImpl.GLOBAL_LOCK.hasQueuedThread(main) && finishCallback.getCount() > 0) {
                LockSupport.parkNanos(100_000);
            }
            finishCallback.countDown();
        }, "test-long-sync-releaser");
        try {
            syncer.start();
            inCallback.await();
            assertFalse(idle.acquirable().trySync(_ -> fail("trySync ran while the global lock was held elsewhere")),
                    "trySync bypassed the global lock held by a sync on another element");

            releaser.start();
            assertTrySyncTakesTheGlobalLock(idle.acquirable());
        } finally {
            finishCallback.countDown();
            syncer.join(5_000);
            releaser.join(5_000);
            otherDispatcher.shutdown();
            idleDispatcher.shutdown();
        }
    }

    private static ThreadDispatcher<Object, Entity> dispatch(Entity entity) {
        Object partition = new Object();
        ThreadDispatcher<Object, Entity> dispatcher = ThreadDispatcher.singleThread(AcquirableTest::rethrow);
        dispatcher.start();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(entity, partition);
        return dispatcher;
    }

    /**
     * trySync on an element nobody else uses takes the global lock before the tick thread lock and gives it
     * back. The global lock is shared by the whole JVM: a sync() in a test class running in parallel holds
     * it until its callback returns, a teleport there can take hundreds of milliseconds, and trySync gives up
     * all that time. The check waits for the lock the way sync() does, interruptibly so that {@code @Timeout}
     * can end it, then trySync takes it a second time: a hold count of 2 in the callback is trySync's own.
     * It then takes the lock from free, retried until {@code @Timeout}: another class may take it between
     * the wait and the call, so a trySync that never succeeds from free fails as a timeout.
     */
    private static void assertTrySyncTakesTheGlobalLock(Acquirable<?> acquirable) throws InterruptedException {
        AtomicInteger holdCount = new AtomicInteger();
        AcquirableImpl.GLOBAL_LOCK.lockInterruptibly();
        try {
            assertTrue(acquirable.trySync(_ -> holdCount.set(AcquirableImpl.GLOBAL_LOCK.getHoldCount())),
                    "trySync gave up on an idle element while the global lock was free to it");
            assertEquals(2, holdCount.get(), "trySync acquired the tick thread lock without the global lock");
            assertEquals(1, AcquirableImpl.GLOBAL_LOCK.getHoldCount(), "trySync did not release the global lock");
        } finally {
            AcquirableImpl.GLOBAL_LOCK.unlock();
        }

        holdCount.set(0);
        while (!acquirable.trySync(_ -> holdCount.set(AcquirableImpl.GLOBAL_LOCK.getHoldCount()))) {
            AcquirableImpl.GLOBAL_LOCK.lockInterruptibly();
            AcquirableImpl.GLOBAL_LOCK.unlock();
        }
        assertEquals(1, holdCount.get(), "trySync acquired the tick thread lock without the free global lock");
        assertEquals(0, AcquirableImpl.GLOBAL_LOCK.getHoldCount(), "trySync did not release the free global lock");
    }
}
