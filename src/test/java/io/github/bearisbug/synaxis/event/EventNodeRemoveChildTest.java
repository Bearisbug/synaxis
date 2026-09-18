package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeRemoveChildTest {

    @Test
    public void removingAChildDetachesItFromTheDispatch() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        AtomicInteger calls = new AtomicInteger();
        child.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        root.addChild(child);

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());

        assertSame(root, root.removeChild(child), "removeChild must be chainable");
        assertNull(child.getParent());
        assertTrue(root.getChildren().isEmpty());

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get(), "A detached child must not be dispatched to anymore");

        // The detached subtree still works on its own
        child.call(new EventNodeTest.EventTest());
        assertEquals(2, calls.get());
    }

    @Test
    public void removingANodeWhichIsNotAChildKeepsBothTreesIntact() {
        var root = EventNode.all("root");
        var other = EventNode.all("other");
        var stranger = EventNode.all("stranger");
        other.addChild(stranger);

        root.removeChild(stranger);
        assertSame(other, stranger.getParent(), "The foreign parent must not be cleared");
        assertEquals(1, other.getChildren().size());
    }

    @Test
    public void aDetachedChildCanBeReattachedElsewhere() {
        var first = EventNode.all("first");
        var second = EventNode.all("second");
        var child = EventNode.all("child");
        AtomicInteger calls = new AtomicInteger();
        child.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());

        first.addChild(child);
        first.removeChild(child);
        second.addChild(child);

        first.call(new EventNodeTest.EventTest());
        assertEquals(0, calls.get());
        second.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }

    @Test
    public void removingAnIntermediateNodeDropsItsWholeSubtree() {
        var root = EventNode.all("root");
        var middle = EventNode.all("middle");
        var leaf = EventNode.all("leaf");
        AtomicInteger calls = new AtomicInteger();
        leaf.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        middle.addChild(leaf);
        root.addChild(middle);

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());

        root.removeChild(middle);
        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }
}
