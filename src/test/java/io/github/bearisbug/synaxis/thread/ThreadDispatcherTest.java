package io.github.bearisbug.synaxis.thread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.github.bearisbug.synaxis.testing.TestUtils.waitUntilCleared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ThreadDispatcherTest {

    private static void rethrow(Throwable throwable) {
        throw new AssertionError("Dispatcher reported an exception", throwable);
    }
    record World() {
    }

    static abstract class Element implements Tickable, AcquirableSource<Element> {
        final Acquirable<Element> acquirable = Acquirable.unassigned(this);

        @Override
        public Acquirable<? extends Element> acquirable() {
            return acquirable;
        }
    }

    @Test
    public void basic() {
        final class Counter implements Tickable {
            int value;

            @Override
            public void tick(long time) {
                value++;
            }
        }
        World world = new World();
        Counter element = new Counter();

        ThreadDispatcher<World, Counter> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();

        assertEquals(0, element.value);
        dispatcher.updateAndAwait(0);
        assertEquals(1, element.value);

        dispatcher.shutdown();
    }

    @Test
    public void basicAcquirable() {
        World world = new World();
        Element element = new Element() {
            @Override
            public void tick(long time) {
            }
        };

        ThreadDispatcher<World, Element> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();

        assertNull(element.acquirable().assignedThread());
        dispatcher.updateAndAwait(0);
        assertNotNull(element.acquirable().assignedThread());

        dispatcher.shutdown();
    }

    @Test
    public void elementTick() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.start();
        assertEquals(1, dispatcher.threads().size());
        assertThrows(Exception.class, () -> dispatcher.threads().add(new TickThread(1, ThreadDispatcherTest::rethrow)));

        var partition = new World();
        Tickable element = (_) -> counter.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter.get());

        dispatcher.updateAndAwait(System.nanoTime());
        dispatcher.updateElement(element, partition); // Should be ignored
        dispatcher.createPartition(partition); // Ignored too
        assertEquals(1, counter.get());

        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(2, counter.get());

        dispatcher.removeElement(element);
        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(2, counter.get());

        dispatcher.shutdown();
    }

    @Test
    public void elementTickLoop() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.start();

        var partition = new World();
        Tickable element = (_) -> counter.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter.get());

        for (int i = 0; i < 100; i++) {
            dispatcher.updateAndAwait(System.nanoTime());
            assertEquals(i + 1, counter.get());
        }

        dispatcher.shutdown();
    }

    @Test
    public void elementTickLoopAsync() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.start();

        var partition = new World();
        Tickable element = (_) -> counter.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter.get());

        final int count = 100;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            Thread.startVirtualThread(() -> {
                dispatcher.updateAndAwait(System.nanoTime());
                latch.countDown();
            });
        }
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            fail("Latch was interrupted");
        }
        assertEquals(count, counter.get());

        dispatcher.shutdown();
    }

    @Test
    public void partitionTick() {
        // Partitions implementing Tickable should be ticked same as elements
        final AtomicInteger counter1 = new AtomicInteger();
        final AtomicInteger counter2 = new AtomicInteger();
        ThreadDispatcher<Tickable, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.start();
        assertEquals(1, dispatcher.threads().size());

        Tickable partition = (_) -> counter1.incrementAndGet();
        Tickable element = (_) -> counter2.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter1.get());
        assertEquals(0, counter2.get());

        for (int i = 0; i < 100; i++) {
            dispatcher.updateAndAwait(System.nanoTime());
            assertEquals(i + 1, counter1.get());
            assertEquals(i + 1, counter2.get());
        }

        dispatcher.deletePartition(partition);
        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(100, counter1.get());
        assertEquals(100, counter2.get());

        dispatcher.shutdown();
    }

    @Test
    public void uniqueThread() {
        // Ensure that partitions are properly dispatched across threads
        final int threadCount = 10;
        ThreadDispatcher<Tickable, Tickable> dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), threadCount, ThreadDispatcherTest::rethrow);
        assertEquals(threadCount, dispatcher.threads().size());
        dispatcher.start();

        final AtomicInteger counter = new AtomicInteger();
        Set<Thread> threads = new CopyOnWriteArraySet<>();
        Set<Tickable> partitions = IntStream.range(0, threadCount)
                .mapToObj(_ -> (Tickable) (_) -> {
                    final Thread thread = Thread.currentThread();
                    assertInstanceOf(TickThread.class, thread);
                    assertEquals(1, ((TickThread) thread).entries.size());
                    assertTrue(threads.add(thread));
                    counter.getAndIncrement();
                })
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(threadCount, partitions.size());

        partitions.forEach(dispatcher::createPartition);
        assertEquals(0, counter.get());

        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(threadCount, counter.get());

        dispatcher.shutdown();
    }

    @Test
    @Timeout(30)
    public void minValueThreadIdMapsToAValidThread() {
        // Math.abs(Integer.MIN_VALUE) is still negative, so the index has to come from a floor modulo
        // (REQ-PROD-019): floorMod(-2^31, 6) = 4
        final int threadCount = 6;
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.dispatcher(
                _ -> Integer.MIN_VALUE, threadCount, ThreadDispatcherTest::rethrow);
        World world = new World();
        Set<Thread> ticked = new CopyOnWriteArraySet<>();
        dispatcher.createPartition(world);
        dispatcher.updateElement(_ -> ticked.add(Thread.currentThread()), world);
        dispatcher.start();
        try {
            dispatcher.updateAndAwait(0);
            assertEquals(Set.of(dispatcher.threads().get(4)), ticked);
        } finally {
            dispatcher.shutdown();
        }
    }

    @Test
    public void threadUpdate() {
        // Ensure that partitions threads are properly updated every tick
        // when RefreshType.ALWAYS is used
        interface Updater extends Tickable {
            int getValue();
        }

        final int threadCount = 10;
        ThreadDispatcher<Updater, Tickable> dispatcher = ThreadDispatcher.dispatcher(new ThreadProvider<>() {
            @Override
            public int findThread(Updater partition) {
                return partition.getValue();
            }

            @Override
            public RefreshType refreshType() {
                return RefreshType.ALWAYS;
            }
        }, threadCount, ThreadDispatcherTest::rethrow);
        assertEquals(threadCount, dispatcher.threads().size());
        dispatcher.start();

        Map<Updater, Thread> threads = new ConcurrentHashMap<>();
        Map<Updater, Thread> threads2 = new ConcurrentHashMap<>();
        Set<Updater> partitions = IntStream.range(0, threadCount)
                .mapToObj(value -> new Updater() {
                    private int v = value;

                    @Override
                    public int getValue() {
                        return v;
                    }

                    @Override
                    public void tick(long time) {
                        final Thread currentThread = Thread.currentThread();
                        assertInstanceOf(TickThread.class, currentThread);
                        if (threads.putIfAbsent(this, currentThread) == null) {
                            this.v = value + 1;
                        } else {
                            assertEquals(value + 1, v);
                            threads2.putIfAbsent(this, currentThread);
                        }
                    }
                }).collect(Collectors.toUnmodifiableSet());
        assertEquals(threadCount, partitions.size());

        partitions.forEach(dispatcher::createPartition);

        dispatcher.updateAndAwait(System.nanoTime());

        dispatcher.refreshThreads();

        dispatcher.updateAndAwait(System.nanoTime());

        assertEquals(threads2.size(), threads.size());
        assertNotEquals(threads, threads2, "Threads have not been updated at all");
        for (var entry : threads.entrySet()) {
            final Thread thread1 = entry.getValue();
            final Thread thread2 = threads2.get(entry.getKey());
            assertNotEquals(thread1, thread2);
        }

        dispatcher.shutdown();
    }

    @Test
    @Timeout(30)
    public void shutdownFromTickThread() throws InterruptedException {
        // Shutting down from a TickThread must not deadlock against the thread awaiting the tick.
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        World world = new World();
        CountDownLatch shutdownCalled = new CountDownLatch(1);
        Tickable element = _ -> {
            dispatcher.shutdown();
            shutdownCalled.countDown();
        };
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();

        Thread ticker = new Thread(() -> dispatcher.updateAndAwait(0), "test-ticker");
        ticker.start();
        assertTrue(shutdownCalled.await(5, TimeUnit.SECONDS), "shutdown() from a TickThread deadlocked");
        ticker.join(5_000);
        assertFalse(ticker.isAlive(), "updateAndAwait never returned");
    }

    @Test
    @Timeout(30)
    public void tickThreadSurvivesAFailingExceptionHandler() throws InterruptedException {
        // A handler that throws (the testing module installs Assertions#fail) used to escape run(),
        // killing the thread before it could count the tick latch down: every later tick hung forever.
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(throwable -> {
            throw new AssertionError("handler failure", throwable);
        });
        World world = new World();
        AtomicInteger ticks = new AtomicInteger();
        Tickable element = _ -> {
            ticks.incrementAndGet();
            throw new IllegalStateException("element failure");
        };
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();
        try {
            awaitTick(dispatcher, "updateAndAwait hung after the exception handler threw");
            assertEquals(1, ticks.get());

            awaitTick(dispatcher, "the tick thread did not survive the failing handler");
            assertEquals(2, ticks.get());
        } finally {
            dispatcher.shutdown();
        }
    }

    @Test
    @Timeout(30)
    public void tickThreadSurvivesAnErrorEscapingTheTickLoop() throws InterruptedException {
        // Same contract one level up: whatever escapes tick() must still be reported and counted down.
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        ThreadDispatcher<World, Tickable> dispatcher = new ThreadDispatcherImpl<>(ThreadProvider.counter(), 1,
                index -> new TickThread(index, reported::add) {
                    @Override
                    protected void tick() {
                        throw new AssertionError("tick loop failure");
                    }
                });
        World world = new World();
        Tickable element = _ -> {
        };
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();
        try {
            awaitTick(dispatcher, "updateAndAwait hung after an Error escaped tick()");
            assertEquals(1, reported.size());

            awaitTick(dispatcher, "the tick thread did not survive the Error");
            assertEquals(2, reported.size());
        } finally {
            dispatcher.shutdown();
        }
    }

    /**
     * Ticks once from a daemon thread: a tick that never completes fails the test instead of blocking
     * the JUnit thread, which no timeout would interrupt.
     */
    private static void awaitTick(ThreadDispatcher<?, ?> dispatcher, String message) throws InterruptedException {
        Thread ticker = new Thread(() -> dispatcher.updateAndAwait(0), "test-ticker");
        ticker.setDaemon(true);
        ticker.start();
        ticker.join(5_000);
        assertFalse(ticker.isAlive(), message);
    }

    @Test
    @Timeout(30)
    public void refreshThreadsReassignsAcquirableOwner() {
        final class Counter extends Element {
            @Override
            public void tick(long time) {
            }
        }
        ThreadDispatcher<World, Counter> dispatcher = ThreadDispatcher.dispatcher(new ThreadProvider<>() {
            private int counter;

            @Override
            public int findThread(World partition) {
                return counter++;
            }

            @Override
            public RefreshType refreshType() {
                return RefreshType.ALWAYS;
            }
        }, 2, ThreadDispatcherTest::rethrow);
        World world = new World();
        Counter element = new Counter();
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();
        dispatcher.updateAndAwait(0);

        final TickThread before = element.acquirable().assignedThread();
        assertNotNull(before);
        dispatcher.refreshThreads();
        dispatcher.updateAndAwait(0);
        assertNotSame(before, element.acquirable().assignedThread(),
                "assignedThread was not updated after the partition moved to another thread");

        dispatcher.shutdown();
    }

    @Test
    @Timeout(30)
    public void removedElementIsReleased() {
        final class Idle implements Tickable {
            @Override
            public void tick(long time) {
            }
        }
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        World world = new World();
        Tickable element = new Idle();
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();
        dispatcher.updateAndAwait(0);

        var ref = new WeakReference<>(element);
        dispatcher.removeElement(element);
        //noinspection UnusedAssignment
        element = null;
        dispatcher.updateAndAwait(0);
        waitUntilCleared(ref);

        dispatcher.shutdown();
    }

    @Test
    @Timeout(30)
    public void deletedPartitionIsReleased() {
        final class Idle implements Tickable {
            @Override
            public void tick(long time) {
            }
        }
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        World world = new World();
        Tickable element = new Idle();
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();
        dispatcher.updateAndAwait(0);

        var worldRef = new WeakReference<>(world);
        var elementRef = new WeakReference<>(element);
        dispatcher.deletePartition(world);
        //noinspection UnusedAssignment
        world = null;
        //noinspection UnusedAssignment
        element = null;
        dispatcher.updateAndAwait(0);
        waitUntilCleared(worldRef);
        waitUntilCleared(elementRef);

        dispatcher.shutdown();
    }

    /**
     * Unloading a chunk deletes its partition, and a map shard unloads up to 256 chunks a tick
     * ({@code docs/DESIGN-M3.md} API-M3-006): a deletion must not scan the other partitions. Counted
     * through equals, which a scan calls on every partition it passes, a hash lookup about once.
     */
    @Test
    public void deletingAPartitionComparesItWithNoOtherPartition() {
        final AtomicLong comparisons = new AtomicLong();
        final class Key {
            private final int id;

            Key(int id) {
                this.id = id;
            }

            @Override
            public boolean equals(Object other) {
                comparisons.incrementAndGet();
                return other instanceof Key key && key.id == id;
            }

            @Override
            public int hashCode() {
                return Integer.hashCode(id);
            }
        }
        ThreadDispatcher<Key, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.start();
        final List<Key> keys = IntStream.range(0, 20_000).mapToObj(Key::new).toList();
        keys.forEach(dispatcher::createPartition);
        // A tick drains at most 4,096 updates
        for (int i = 0; i < 6; i++) dispatcher.updateAndAwait(0);

        comparisons.set(0);
        keys.subList(keys.size() - 256, keys.size()).forEach(dispatcher::deletePartition);
        dispatcher.updateAndAwait(0);

        assertTrue(comparisons.get() <= 2 * 256, "deleting 256 of 20,000 partitions compared keys "
                + comparisons.get() + " times");
        dispatcher.shutdown();
    }

    /** The tick thread drops a deleted partition by hash and still ticks the others in the order they came. */
    @Test
    public void aTickThreadKeepsTheOrderOfItsPartitionsAcrossDeletions() {
        final List<Integer> ticked = new CopyOnWriteArrayList<>();
        final class Numbered implements Tickable {
            private final int number;

            Numbered(int number) {
                this.number = number;
            }

            @Override
            public void tick(long time) {
                ticked.add(number);
            }
        }
        ThreadDispatcher<Tickable, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        dispatcher.start();
        final List<Numbered> partitions = IntStream.range(0, 10).mapToObj(Numbered::new).toList();
        partitions.forEach(dispatcher::createPartition);
        dispatcher.updateAndAwait(0);
        dispatcher.deletePartition(partitions.get(3));
        dispatcher.deletePartition(partitions.get(7));
        ticked.clear();

        dispatcher.updateAndAwait(0);

        assertEquals(List.of(0, 1, 2, 4, 5, 6, 8, 9), ticked);
        assertInstanceOf(LinkedHashSet.class, dispatcher.threads().getFirst().entries,
                "a tick thread removes a partition by hash, not by a scan of the list");
        dispatcher.shutdown();
    }

    /**
     * A tick thread with nothing to tick that wakes up anyway (a spurious return of {@code park}, a
     * permit left over) runs no tick and counts no latch down: the tick ends when the thread that has
     * the partition is done with it, not before.
     */
    @Test
    @Timeout(30)
    public void aSpuriousWakeupOfAnIdleThreadDoesNotEndTheTickEarly() throws InterruptedException {
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), 2,
                ThreadDispatcherTest::rethrow);
        World world = new World();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean hold = new AtomicBoolean(true);
        dispatcher.createPartition(world);
        dispatcher.updateElement(_ -> {
            if (!hold.get()) return;
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }, world);
        dispatcher.start();
        try {
            Thread ticker = new Thread(() -> dispatcher.updateAndAwait(0), "test-ticker");
            ticker.setDaemon(true);
            ticker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the element never ticked");
            final TickThread idle = dispatcher.threads().get(1);
            assertTrue(idle.entries.isEmpty(), "the second thread has no partition");
            final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (System.nanoTime() - until < 0) {
                LockSupport.unpark(idle);
                Thread.sleep(1);
            }
            assertTrue(ticker.isAlive(), "updateAndAwait returned while the element was still ticking");

            hold.set(false);
            release.countDown();
            ticker.join(5_000);
            assertFalse(ticker.isAlive(), "updateAndAwait never returned");
            for (int i = 0; i < 10; i++) awaitTick(dispatcher, "a later tick hung");
        } finally {
            release.countDown();
            dispatcher.shutdown();
        }
    }

    /**
     * An interrupt does not end the wait for the tick threads: the next update would otherwise change
     * the partitions of a thread still ticking them. The wait ends with the tick and the interrupt
     * stays set.
     */
    @Test
    @Timeout(30)
    public void anInterruptedUpdateWaitsForTheTickAndKeepsTheInterrupt() throws InterruptedException {
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        World world = new World();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatcher.createPartition(world);
        dispatcher.updateElement(_ -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }, world);
        dispatcher.start();
        try {
            AtomicBoolean interruptedAfter = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread ticker = new Thread(() -> {
                try {
                    dispatcher.updateAndAwait(0);
                    interruptedAfter.set(Thread.currentThread().isInterrupted());
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-ticker");
            ticker.setDaemon(true);
            ticker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the element never ticked");

            ticker.interrupt();
            ticker.join(200);
            assertTrue(ticker.isAlive(), "updateAndAwait gave up on the tick: " + failure.get());

            release.countDown();
            ticker.join(5_000);
            assertFalse(ticker.isAlive(), "updateAndAwait never returned");
            assertNull(failure.get());
            assertTrue(interruptedAfter.get(), "the interrupt is kept for the caller");
        } finally {
            release.countDown();
            dispatcher.shutdown();
        }
    }

    /**
     * An element that leaves an {@link Acquired} of an element of another thread locked keeps the
     * global lock and the lock of that thread: the tick thread reports it by name. Here the handler
     * unlocks it, on the tick thread that holds it, so that the next tick can run.
     */
    @Test
    @Timeout(30)
    public void anElementThatLeavesAnAcquiredLockedIsReportedByName() throws InterruptedException {
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        final AtomicReference<Acquired<? extends Element>> leaked = new AtomicReference<>();
        final AtomicBoolean released = new AtomicBoolean();
        ThreadDispatcher<Object, Element> dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), 2, throwable -> {
            reported.add(throwable);
            if (String.valueOf(throwable.getMessage()).endsWith("left an Acquired locked")
                    && released.compareAndSet(false, true)) leaked.get().unlock();
        });
        final class Target extends Element {
            @Override
            public void tick(long time) {
            }
        }
        final Target target = new Target();
        final AtomicBoolean armed = new AtomicBoolean();
        final Element leaker = new Element() {
            @Override
            public void tick(long time) {
                if (!armed.getAndSet(false)) return;
                leaked.set(target.acquirable().lock());
                throw new IllegalStateException("used what it acquired and failed");
            }

            @Override
            public String toString() {
                return "the leaking element";
            }
        };
        // Ticked after the leaker on its thread: gives the lock back if the tick thread did not report
        final Element cleaner = new Element() {
            @Override
            public void tick(long time) {
                if (leaked.get() != null && released.compareAndSet(false, true)) leaked.get().unlock();
            }
        };
        // Two partitions that are not equal, so that each gets a thread of its own
        Object first = new Object();
        Object second = new Object();
        dispatcher.createPartition(first);
        dispatcher.createPartition(second);
        dispatcher.updateElement(leaker, first);
        dispatcher.updateElement(cleaner, first);
        dispatcher.updateElement(target, second);
        dispatcher.start();
        try {
            awaitTick(dispatcher, "the first tick hung");
            assertNotSame(leaker.acquirable().assignedThread(), target.acquirable().assignedThread());

            armed.set(true);
            awaitTick(dispatcher, "the tick of the leak hung");
            awaitTick(dispatcher, "the tick after the leak hung");

            assertEquals(List.of("used what it acquired and failed", "the leaking element left an Acquired locked"),
                    reported.stream().map(Throwable::getMessage).toList());
            assertInstanceOf(IllegalStateException.class, reported.get(1));
        } finally {
            dispatcher.shutdown();
        }
    }

    /** Closing what was unlocked already does nothing, so that try-with-resources can follow an unlock. */
    @Test
    @Timeout(30)
    public void closingAnAcquiredThatWasUnlockedDoesNothing() throws InterruptedException {
        ThreadDispatcher<Object, Element> dispatcher = ThreadDispatcher.singleThread(ThreadDispatcherTest::rethrow);
        final class Idle extends Element {
            @Override
            public void tick(long time) {
            }
        }
        final Idle idle = new Idle();
        Object partition = new Object();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(idle, partition);
        dispatcher.start();
        try {
            awaitTick(dispatcher, "the first tick hung");
            assertNotNull(idle.acquirable().assignedThread());
            try (Acquired<? extends Element> acquired = idle.acquirable().lock()) {
                assertTrue(Acquirable.holdsGlobalLock(), "held from another thread than the element's");
                acquired.unlock();
            }
            assertFalse(Acquirable.holdsGlobalLock(), "unlocked once, by unlock()");
            try (Acquired<? extends Element> acquired = idle.acquirable().lock()) {
                assertSame(idle, acquired.get());
            }
            assertFalse(Acquirable.holdsGlobalLock(), "unlocked by close()");
            awaitTick(dispatcher, "a tick after the acquisitions hung");
        } finally {
            dispatcher.shutdown();
        }
    }
}
