package dev.gameengine.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventNodeMappedConcurrencyTest {

    record OwnerEvent(Object owner) implements Event {
    }

    /**
     * Owners share hash codes so that the surviving ones sit in a bucket full of collected owners.
     */
    static final class Owner {
        private final int hash;

        Owner(int hash) {
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final EventFilter<OwnerEvent, Object> FILTER =
            EventFilter.from(OwnerEvent.class, Object.class, OwnerEvent::owner);

    @Test
    @Timeout(60)
    public void concurrentDispatchAfterMappedOwnersCollected() throws InterruptedException {
        final int threadCount = 4;
        final int iterations = 5_000;

        var node = EventNode.all("main");
        var aCalls = new AtomicInteger();
        var bCalls = new AtomicInteger();

        // Mapped nodes whose owner is dropped right after the dispatch consumer has been built
        Owner[] garbage = new Owner[2048];
        for (int i = 0; i < garbage.length; i++) {
            Owner owner = new Owner(i % 256);
            garbage[i] = owner;
            node.map(owner, FILTER).addListener(OwnerEvent.class, _ -> {
            });
        }
        Owner a = new Owner(0);
        Owner b = new Owner(0);
        // Keep the mapped nodes strongly reachable, they are only weakly held by the parent node
        var aNode = node.map(a, FILTER);
        var bNode = node.map(b, FILTER);
        aNode.addListener(OwnerEvent.class, _ -> aCalls.incrementAndGet());
        bNode.addListener(OwnerEvent.class, _ -> bCalls.incrementAndGet());

        // Build the dispatch consumer while every owner is still alive, then let most of them die
        node.call(new OwnerEvent(a));
        aCalls.set(0);
        Arrays.fill(garbage, null);
        System.gc();

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final Owner target = i % 2 == 0 ? a : b;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                for (int j = 0; j < iterations; j++) node.call(new OwnerEvent(target));
            }, "mapped-dispatch-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) worker.join();

        final int expected = threadCount / 2 * iterations;
        assertEquals(expected, aCalls.get(), "mapped listener calls were lost");
        assertEquals(expected, bCalls.get(), "mapped listener calls were lost");
        // Keep the mapped nodes alive until the very end
        assertEquals(node, aNode.getParent());
        assertEquals(node, bNode.getParent());
    }
}
