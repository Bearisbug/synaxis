package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeChildInvalidationTest {

    @Test
    public void aListenerAddedAfterTheFirstDispatchIsPickedUp() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        root.addChild(child);
        // Compiles and caches the (empty) dispatch of root
        root.call(new EventNodeTest.EventTest());

        AtomicInteger calls = new AtomicInteger();
        child.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }

    @Test
    public void aListenerAddedToAGrandchildIsPickedUpByTheRoot() {
        var root = EventNode.all("root");
        var middle = EventNode.all("middle");
        var leaf = EventNode.all("leaf");
        root.addChild(middle);
        middle.addChild(leaf);
        root.call(new EventNodeTest.EventTest());

        AtomicInteger calls = new AtomicInteger();
        leaf.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }

    @Test
    public void attachingAPopulatedSubtreeAfterTheFirstDispatchIsPickedUp() {
        var root = EventNode.all("root");
        root.call(new EventNodeTest.EventTest());
        assertFalse(root.hasListener(EventNodeTest.EventTest.class));

        AtomicInteger calls = new AtomicInteger();
        var child = EventNode.all("child");
        child.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        root.addChild(child);

        assertTrue(root.hasListener(EventNodeTest.EventTest.class));
        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }

    @Test
    public void removingTheLastListenerMakesTheNodeReportNoListener() {
        var root = EventNode.all("root");
        var listener = EventListener.of(EventNodeTest.EventTest.class, _ -> {
        });
        root.addListener(listener);
        assertTrue(root.hasListener(EventNodeTest.EventTest.class));

        root.removeListener(listener);
        assertFalse(root.hasListener(EventNodeTest.EventTest.class));
    }

    @Test
    public void removingAnUnknownListenerLeavesTheDispatchUntouched() {
        var root = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        root.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());

        root.removeListener(EventListener.of(EventNodeTest.EventTest.class, _ -> {
        }));
        root.removeListener(EventListener.of(EventNodeTest.CancellableTest.class, _ -> {
        }));

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }
}
