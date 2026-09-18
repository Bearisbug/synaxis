package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.event.trait.RecursiveEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeRecursiveInvalidationTest {

    static class Recursive3 extends EventNodeTest.Recursive2 {
    }

    // The top of a recursive chain: its own super class is Object, which is not an event
    static class TopRecursive implements RecursiveEvent {
    }

    static class TopRecursiveChild extends TopRecursive {
    }

    @Test
    public void aSuperTypeListenerAddedAfterTheSubTypeHandleWasCachedStillFires() {
        // The sub type handle is compiled first, adding a super type listener afterwards has to
        // invalidate it because a recursive event walks its super classes at dispatch
        var node = EventNode.all("main");
        var handle = node.getHandle(EventNodeTest.Recursive2.class);
        assertFalse(handle.hasListener());

        AtomicInteger calls = new AtomicInteger();
        node.addListener(EventNodeTest.Recursive1.class, _ -> calls.incrementAndGet());

        assertTrue(handle.hasListener());
        handle.call(new EventNodeTest.Recursive2());
        assertEquals(1, calls.get());
    }

    @Test
    public void aRecursiveEventWalksEveryRecursiveSuperClass() {
        var node = EventNode.all("main");
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicInteger third = new AtomicInteger();
        node.addListener(EventNodeTest.Recursive1.class, _ -> first.incrementAndGet());
        node.addListener(EventNodeTest.Recursive2.class, _ -> second.incrementAndGet());
        node.addListener(Recursive3.class, _ -> third.incrementAndGet());

        node.call(new Recursive3());
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(1, third.get());

        node.call(new EventNodeTest.Recursive2());
        assertEquals(2, first.get());
        assertEquals(2, second.get());
        assertEquals(1, third.get(), "A super type event must not reach its sub type listeners");
    }

    @Test
    public void aSuperTypeListenerAddedThroughAChildAlsoInvalidatesTheRootHandle() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        root.addChild(child);
        var handle = root.getHandle(EventNodeTest.Recursive2.class);
        assertFalse(handle.hasListener());

        AtomicInteger calls = new AtomicInteger();
        child.addListener(EventNodeTest.Recursive1.class, _ -> calls.incrementAndGet());

        handle.call(new EventNodeTest.Recursive2());
        assertEquals(1, calls.get());
    }

    @Test
    public void theRecursionStopsAtTheFirstNonRecursiveSuperClass() {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        node.addListener(TopRecursive.class, _ -> calls.incrementAndGet());

        node.call(new TopRecursiveChild());
        assertEquals(1, calls.get());

        // Object is not a RecursiveEvent, the walk must stop there instead of failing
        node.call(new TopRecursive());
        assertEquals(2, calls.get());
    }
}
