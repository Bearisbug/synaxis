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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeChildConcurrencyTest {

    private static final int THREADS = 4;
    private static final int ITERATIONS = 300;

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
    public void attachingAndDetachingSubtreesUnderADispatchLeavesACleanRoot() throws InterruptedException {
        var root = EventNode.all("root");
        List<Runnable> bodies = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int id = i;
            bodies.add(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    var child = EventNode.all("child-" + id + '-' + j);
                    child.addListener(EventNodeTest.EventTest.class, _ -> {
                    });
                    root.addChild(child);
                    root.call(new EventNodeTest.EventTest());
                    root.removeChild(child);
                }
            });
        }
        runAll(bodies);

        assertTrue(root.getChildren().isEmpty());
        assertFalse(root.hasListener(EventNodeTest.EventTest.class));
    }

    @Test
    public void aChildAddedConcurrentlyIsAlwaysReachableAfterTheJoin() throws InterruptedException {
        var root = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        List<Runnable> bodies = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int id = i;
            bodies.add(() -> {
                var child = EventNode.all("child-" + id);
                child.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
                root.addChild(child);
            });
        }
        runAll(bodies);

        assertEquals(THREADS, root.getChildren().size());
        root.call(new EventNodeTest.EventTest());
        assertEquals(THREADS, calls.get());
    }

    @Test
    public void concurrentPriorityChangesNeverLoseAChildFromTheDispatch() throws InterruptedException {
        var root = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        List<EventNode<Event>> children = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            var child = EventNode.all("child-" + i);
            child.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
            root.addChild(child);
            children.add(child);
        }

        List<Runnable> bodies = new ArrayList<>();
        for (EventNode<Event> child : children) {
            bodies.add(() -> {
                for (int j = 0; j < ITERATIONS; j++) child.setPriority(j);
            });
        }
        bodies.add(() -> {
            for (int j = 0; j < ITERATIONS; j++) root.call(new EventNodeTest.EventTest());
        });
        runAll(bodies);

        final int before = calls.get();
        root.call(new EventNodeTest.EventTest());
        assertEquals(before + THREADS, calls.get(), "Every child must still be in the dispatch");
    }
}
