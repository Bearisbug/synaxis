package io.github.bearisbug.synaxis.game.playkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalQueue} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}): once per player, in the order
 * they joined, taken a round at a time, from any thread.
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
}
