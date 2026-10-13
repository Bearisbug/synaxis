package io.github.bearisbug.synaxis.game.playkit;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalQueue} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}): once per player, in the order
 * they joined, taken a round at a time, from any thread. The cases of two threads at once stop the first
 * call inside the queue, holding its lock, and let it go only once the second is seen waiting for that
 * lock, so that their order does not depend on the scheduler.
 */
class LocalQueueTest {

    @Test
    void aPlayerIsQueuedOnceInTheOrderOfJoining() {
        final LocalQueue<String> queue = new LocalQueue<>();

        assertTrue(queue.join("a"));
        assertTrue(queue.join("b"));
        assertTrue(queue.join("c"));
        assertFalse(queue.join("a"), "a player was queued twice");
        assertEquals(3, queue.size());
        assertTrue(queue.leave("b"));
        assertFalse(queue.leave("b"), "a player left a queue it was not in");

        assertEquals(List.of("a", "c"), queue.players());
    }

    @Test
    void fewerThanTheMinimumAreNotTaken() {
        final LocalQueue<String> queue = new LocalQueue<>();
        queue.join("a");

        assertEquals(List.of(), queue.take(2, 8));
        assertEquals(List.of("a"), queue.players(), "a take that found too few players changed the queue");
    }

    @Test
    void aTakeTakesAtMostTheMaximumFirstComeFirst() {
        final LocalQueue<Integer> queue = new LocalQueue<>();
        IntStream.rangeClosed(1, 10).forEach(queue::join);

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), queue.take(2, 8));
        assertEquals(List.of(9, 10), queue.players());
        assertEquals(List.of(9, 10), queue.take(0, Integer.MAX_VALUE));
        assertEquals(0, queue.size());
        assertEquals(List.of(), queue.take(0, 8));
    }

    @Test
    void theBoundsOfATakeAreChecked() {
        final LocalQueue<String> queue = new LocalQueue<>();

        assertThrows(IllegalArgumentException.class, () -> queue.take(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> queue.take(3, 2));
    }

    /** Joins on many threads and takes on many threads at once lose and repeat nobody. */
    @Test
    void joinsAndTakesOnManyThreadsLoseNobody() throws InterruptedException {
        final LocalQueue<Integer> queue = new LocalQueue<>();
        final int threads = 8;
        final int each = 1_000;
        runTogether(threads, thread -> {
            for (int i = 0; i < each; i++) queue.join(thread * each + i);
        });
        assertEquals(threads * each, queue.size());

        final List<List<Integer>> taken = new ArrayList<>();
        for (int i = 0; i < threads; i++) taken.add(new ArrayList<>());
        runTogether(threads, thread -> {
            for (List<Integer> round = queue.take(1, 37); !round.isEmpty(); round = queue.take(1, 37))
                taken.get(thread).addAll(round);
        });

        final Set<Integer> everyone = new HashSet<>();
        int count = 0;
        for (final List<Integer> part : taken) {
            everyone.addAll(part);
            count += part.size();
        }
        assertEquals(threads * each, count, "a player was taken twice");
        assertEquals(threads * each, everyone.size(), "a player was lost");
        assertEquals(0, queue.size());
    }

    /**
     * A take that comes while a join is under way waits for it and takes that player too: of two players
     * queued, the one still joining is not left out of a round of two.
     */
    @Test
    void aTakeWaitsForAJoinUnderWayAndTakesThatPlayer() throws Exception {
        final LocalQueue<Object> queue = new LocalQueue<>();
        final Gate dave = new Gate("dave");
        queue.join("alice");
        try {
            final Call<Boolean> join = dave.stopInside(() -> queue.join(dave));
            final Call<List<Object>> take = onItsOwnThread(() -> queue.take(2, 8));
            assertWaitsFor(join, take, "a take");
            dave.open();

            assertTrue(join.result(), "dave was not queued");
            assertEquals(List.of("alice", dave), take.result(), "the take did not see the join it waited for");
            assertEquals(0, queue.size());
        } finally {
            dave.open();
        }
    }

    /** A second join of a player whose first join is under way waits for it and is refused. */
    @Test
    void aSecondJoinOfAPlayerWaitsForTheFirstAndIsRefused() throws Exception {
        final LocalQueue<Object> queue = new LocalQueue<>();
        final Gate alice = new Gate("alice");
        try {
            final Call<Boolean> first = alice.stopInside(() -> queue.join(alice));
            final Call<Boolean> second = onItsOwnThread(() -> queue.join(alice));
            assertWaitsFor(first, second, "the second join");
            alice.open();

            assertTrue(first.result(), "the first join was refused");
            assertFalse(second.result(), "alice was queued twice");
            assertEquals(List.of(alice), queue.players());
        } finally {
            alice.open();
        }
    }

    /**
     * A take that comes while a player is leaving waits for the leave and does not take that player: a
     * player who leaves before the round is taken is not in it.
     */
    @Test
    void aTakeWaitsForALeaveUnderWayAndLeavesThatPlayerOut() throws Exception {
        final LocalQueue<Object> queue = new LocalQueue<>();
        final Gate bob = new Gate("bob");
        queue.join("alice");
        queue.join(bob);
        queue.join("carol");
        try {
            final Call<Boolean> leave = bob.stopInside(() -> queue.leave(bob));
            final Call<List<Object>> take = onItsOwnThread(() -> queue.take(1, 8));
            assertWaitsFor(leave, take, "a take");
            bob.open();

            assertTrue(leave.result(), "bob did not leave");
            assertEquals(List.of("alice", "carol"), take.result(), "the round took a player who had left");
            assertEquals(0, queue.size());
        } finally {
            bob.open();
        }
    }

    private static void runTogether(int threads, IntConsumer body) throws InterruptedException {
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    body.accept(thread);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "the threads did not finish in 10 s");
    }

    /**
     * Waits until {@code waiting} is blocked on a lock the stopped call holds, which inside the queue can
     * only be the queue's own; fails as soon as {@code waiting} finished instead.
     */
    private static void assertWaitsFor(Call<?> stopped, Call<?> waiting, String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiting.thread().threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == stopped.thread().threadId()) return;
            assertFalse(waiting.future().isDone(), what + " went through while a call stopped inside the queue");
            assertTrue(System.nanoTime() - deadline < 0, what + " did not wait for the queue's lock within 10 s");
            Thread.sleep(1);
        }
    }

    /** Starts a call of the queue on a thread of its own. */
    private static <R> Call<R> onItsOwnThread(Supplier<R> call) {
        final CompletableFuture<R> future = new CompletableFuture<>();
        final Thread thread = Thread.ofPlatform().daemon().start(() -> {
            try {
                future.complete(call.get());
            } catch (RuntimeException | Error e) {
                future.completeExceptionally(e);
            }
        });
        return new Call<>(thread, future);
    }

    /**
     * A call of the queue on a thread of its own.
     *
     * @param thread the thread
     * @param future completes with what the call returned
     * @param <R>    what the call returns
     */
    private record Call<R>(Thread thread, CompletableFuture<R> future) {

        R result() throws Exception {
            return future.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * A queued player whose {@code hashCode}, which the queue's set asks for inside {@code join} and
     * {@code leave} while the queue's lock is held, stops the one call {@link #stopInside} starts until
     * {@link #open}. Every other call goes straight through.
     */
    private static final class Gate {

        private final String name;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final CountDownLatch opened = new CountDownLatch(1);

        Gate(String name) {
            this.name = name;
        }

        /** Starts {@code call} on a thread of its own and returns once it stopped at this gate. */
        <R> Call<R> stopInside(Supplier<R> call) throws InterruptedException {
            armed.set(true);
            final Call<R> started = onItsOwnThread(call);
            assertTrue(stopped.await(10, TimeUnit.SECONDS), "the call never asked the queue's set for " + name);
            return started;
        }

        void open() {
            opened.countDown();
        }

        @Override
        public int hashCode() {
            if (armed.compareAndSet(true, false)) {
                stopped.countDown();
                try {
                    if (!opened.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("nobody opened the gate of " + name);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
