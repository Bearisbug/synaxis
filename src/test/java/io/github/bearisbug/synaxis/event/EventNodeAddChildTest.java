package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeAddChildTest {

    @Test
    public void addingAChildSetsItsParentAndMakesItReachable() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        AtomicInteger calls = new AtomicInteger();
        child.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());

        assertSame(root, root.addChild(child), "addChild must be chainable");
        assertSame(root, child.getParent());
        assertTrue(root.getChildren().contains(child));

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }

    @Test
    public void everySiblingReceivesTheEventOnce() {
        var root = EventNode.all("root");
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        root.addChild(EventNode.all("first").addListener(EventNodeTest.EventTest.class, _ -> first.incrementAndGet()));
        root.addChild(EventNode.all("second").addListener(EventNodeTest.EventTest.class, _ -> second.incrementAndGet()));

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    public void readdingTheSameChildIsRejectedAsAnAlreadyParentedNode() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        root.addChild(child);
        assertThrows(IllegalStateException.class, () -> root.addChild(child));
        assertEquals(1, root.getChildren().size());
    }

    @Test
    public void aNodeCannotBeAdoptedTwice() {
        var first = EventNode.all("first");
        var second = EventNode.all("second");
        var child = EventNode.all("child");
        first.addChild(child);
        assertThrows(IllegalStateException.class, () -> second.addChild(child));
        assertSame(first, child.getParent());
    }

    @Test
    public void aParentCannotBecomeItsOwnChildsChild() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        root.addChild(child);
        assertThrows(IllegalStateException.class, () -> child.addChild(root));
    }

    @Test
    public void aNodeCannotBeItsOwnChild() {
        // A cycle makes both the invalidation walk and the dispatch compilation recurse forever
        var root = EventNode.all("root");
        assertThrows(IllegalStateException.class, () -> root.addChild(root));
        assertTrue(root.getChildren().isEmpty(), "The rejected node must not stay in the children");
    }

    @Test
    public void theChildrenViewIsUnmodifiable() {
        var root = EventNode.all("root");
        var children = root.getChildren();
        assertThrows(UnsupportedOperationException.class, () -> children.add(EventNode.all("child")));
    }
}
