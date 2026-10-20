package io.github.bearisbug.synaxis.thread;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An element changing threads while a thread that is not a tick thread holds it, or is on its way to
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}): the dispatcher hands an element to another thread
 * only under the global lock, and an acquisition that read the old thread enters the new one.
 * <p>
 * Each interleaving is held open until the thread it waits for shows up in a lock's queue, or,
 * where the fix is missing, until the race it would prevent has happened. Isolated: the global lock
 * is one per JVM, and a test class running alongside could hold it in the middle of an interleaving.
 */
@Isolated
public class AcquirableHandoverTest {
    private static final long PATIENCE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static void rethrow(Throwable throwable) {
        throw new AssertionError("Dispatcher reported an exception", throwable);
    }

    private static final class Probe implements Tickable, AcquirableSource<Probe> {
        final Acquirable<Probe> acquirable = Acquirable.unassigned(this);
        final AtomicInteger ticksWhileHeld = new AtomicInteger();
        volatile boolean held;
        volatile @Nullable Thread tickedOn;

        @Override
        public void tick(long time) {
            if (held) ticksWhileHeld.incrementAndGet();
            tickedOn = Thread.currentThread();
        }

        @Override
        public Acquirable<Probe> acquirable() {
            return acquirable;
        }
    }

    /**
     * A partition whose first hash, taken by the dispatcher when it processes the partition's load,
     * waits until it is released: the dispatcher stops there, in the middle of its updates.
     */
    private static final class Stall {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public int hashCode() {
            if (first.compareAndSet(true, false)) {
                reached.countDown();
                awaitLatch(release);
            }
            return System.identityHashCode(this);
        }
    }

    /** A partition whose first hash tells that the dispatcher processed every update before it. */
    private static final class Marker {
        final CountDownLatch passed = new CountDownLatch(1);

        @Override
        public int hashCode() {
            passed.countDown();
            return System.identityHashCode(this);
        }
    }

    /**
     * A thread holds an element through its acquirable from before a tick, and moves it to a partition
     * of another tick thread meanwhile: the dispatcher hands it over once the holder let it go, so the
     * other thread never ticks it while it is held. The dispatcher's wait is the ticking thread's own
     * acquisition wait.
     */
    @Test
    @Timeout(30)
    public void anElementHeldFromAnotherThreadStaysOnItsThreadUntilReleased() throws InterruptedException {
        final ThreadDispatcher<Object, Probe> dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), 2,
                AcquirableHandoverTest::rethrow);
        final Object first = new Object(), second = new Object(); // threads 0 and 1
        final Probe element = new Probe();
        dispatcher.createPartition(first);
        dispatcher.createPartition(second);
        dispatcher.updateElement(element, first);
        dispatcher.start();
        try {
            dispatcher.updateAndAwait(0);
            final TickThread firstThread = dispatcher.threads().get(0), secondThread = dispatcher.threads().get(1);
            assertSame(firstThread, element.acquirable().assignedThread());

            final CountDownLatch holding = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final Thread holder = Thread.ofPlatform().name("test-holder").start(() -> element.acquirable().sync(_ -> {
                element.held = true;
                dispatcher.updateElement(element, second);
                holding.countDown();
                awaitLatch(release);
                element.held = false;
            }));
            awaitLatch(holding);
            final EngineThread ticker = new EngineThread("test-ticker") {
                @Override
                public void run() {
                    dispatcher.updateAndAwait(0);
                }
            };
            ticker.start();
            // Released once the dispatcher waits for the holder; without that wait, once the other
            // thread ticked the element under the holder's feet
            await(() -> AcquirableImpl.GLOBAL_LOCK.hasQueuedThread(ticker) || element.ticksWhileHeld.get() > 0,
                    "the dispatcher waiting for the holder");
            release.countDown();
            holder.join(10_000);
            ticker.join(10_000);

            assertEquals(0, element.ticksWhileHeld.get(), "ticked on " + element.tickedOn + " while held on "
                    + firstThread.getName());
            assertSame(secondThread, element.acquirable().assignedThread());
            assertSame(secondThread, element.tickedOn, "ticked by the thread of its new partition once released");
            assertTrue(ticker.acquireWaitNanos() > 0, "the dispatcher's wait for the holder is the ticker's");
        } finally {
            dispatcher.shutdown();
        }
    }

    /** How a thread acquires the element, with the work it does while holding it. */
    enum Acquisition {
        SYNC {
            @Override
            void acquire(Probe element, Runnable work) {
                element.acquirable().sync(_ -> work.run());
            }
        },
        LOCK {
            @Override
            void acquire(Probe element, Runnable work) {
                final Acquired<Probe> acquired = element.acquirable().lock();
                try {
                    work.run();
                } finally {
                    acquired.unlock();
                }
            }
        };

        abstract void acquire(Probe element, Runnable work);
    }

    /**
     * A thread reads the element's tick thread just before the dispatcher hands the element to
     * another one, then waits for the global lock the dispatcher holds: once it gets the locks, the
     * element is the new thread's, and the acquisition still owns it.
     */
    @ParameterizedTest
    @EnumSource(Acquisition.class)
    @Timeout(30)
    public void anAcquisitionThatReadTheOldThreadOwnsTheElement(Acquisition acquisition) throws InterruptedException {
        final ThreadDispatcher<Object, Probe> dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), 2,
                AcquirableHandoverTest::rethrow);
        final Object first = new Object(), second = new Object(); // threads 0 and 1
        final Probe element = new Probe();
        dispatcher.createPartition(first);
        dispatcher.createPartition(second);
        dispatcher.updateElement(element, first);
        dispatcher.start();
        try {
            dispatcher.updateAndAwait(0);
            assertSame(dispatcher.threads().get(0), element.acquirable().assignedThread());

            // In this order: the dispatcher stops before it moves the element, and passes the marker after
            final Stall stall = new Stall();
            final Marker marker = new Marker();
            dispatcher.createPartition(stall);
            dispatcher.updateElement(element, second);
            dispatcher.createPartition(marker);
            final Thread ticker = Thread.ofPlatform().name("test-ticker").start(() -> dispatcher.updateAndAwait(0));
            awaitLatch(stall.reached);

            final CountDownLatch working = new CountDownLatch(1);
            final AtomicBoolean ownedOnceMoved = new AtomicBoolean();
            final Thread acquirer = Thread.ofPlatform().name("test-acquirer").start(() -> acquisition.acquire(element, () -> {
                working.countDown();
                awaitLatch(marker.passed);
                ownedOnceMoved.set(element.acquirable().isOwned());
            }));
            // Released once the acquirer waits for the global lock; without the dispatcher holding it,
            // once the acquirer works on the element
            await(() -> AcquirableImpl.GLOBAL_LOCK.hasQueuedThread(acquirer) || working.getCount() == 0,
                    "the acquirer waiting for the dispatcher");
            stall.release.countDown();
            acquirer.join(10_000);
            ticker.join(10_000);

            assertSame(dispatcher.threads().get(1), element.acquirable().assignedThread());
            assertTrue(ownedOnceMoved.get(), acquisition + " held the lock of the thread the element left");
        } finally {
            dispatcher.shutdown();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE_NANOS, TimeUnit.NANOSECONDS)) fail("a latch was not released within 10 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        final long deadline = System.nanoTime() + PATIENCE_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("no " + what + " within 10 s");
            LockSupport.parkNanos(100_000);
        }
    }
}
