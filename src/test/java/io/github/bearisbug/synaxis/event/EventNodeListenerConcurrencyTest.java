package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class EventNodeListenerConcurrencyTest {

    private static final int THREADS = 4;
    private static final int ITERATIONS = 500;

    private static void runAll(List<Runnable> bodies) throws InterruptedException {
        var barrier = new CyclicBarrier(bodies.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>(bodies.size());
        for (Runnable body : bodies) {
            var thread = new Thread(() -> {
                try {
                    barrier.await();
                    body.run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        assertNull(failure.get(), () -> "A worker failed: " + failure.get());
    }

    @Test
    public void everyConcurrentlyAddedListenerEndsUpInTheDispatch() throws InterruptedException {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        List<Runnable> bodies = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            bodies.add(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    node.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
                }
            });
        }
        runAll(bodies);

        node.call(new EventNodeTest.EventTest());
        assertEquals(THREADS * ITERATIONS, calls.get(), "No listener may be lost by a concurrent add");
    }

    @Test
    public void addAndRemoveOfTheSameListenerLeavesNoResidue() throws InterruptedException {
        var node = EventNode.all("main");
        List<Runnable> bodies = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            bodies.add(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    var listener = EventListener.of(EventNodeTest.EventTest.class, _ -> {
                    });
                    node.addListener(listener);
                    node.call(new EventNodeTest.EventTest());
                    node.removeListener(listener);
                }
            });
        }
        runAll(bodies);

        assertFalse(node.hasListener(EventNodeTest.EventTest.class),
                "Every listener was removed, the node must report an empty dispatch");
    }

    @Test
    public void aStableListenerSeesEveryConcurrentCall() throws InterruptedException {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        node.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());

        List<Runnable> bodies = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            bodies.add(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    node.call(new EventNodeTest.EventTest());
                }
            });
        }
        // One more worker keeps invalidating the compiled dispatch under the callers
        bodies.add(() -> {
            for (int j = 0; j < ITERATIONS; j++) {
                var noise = EventListener.of(EventNodeTest.EventTest.class, _ -> {
                });
                node.addListener(noise);
                node.removeListener(noise);
            }
        });
        runAll(bodies);

        assertEquals(THREADS * ITERATIONS, calls.get(),
                "A concurrent invalidation may not drop a dispatch");
    }
}
