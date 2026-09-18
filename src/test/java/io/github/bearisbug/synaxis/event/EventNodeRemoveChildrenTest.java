package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeRemoveChildrenTest {

    @Test
    public void everyMatchingChildIsRemovedAtTheSameDepth() {
        var root = EventNode.all("root");
        var first = EventNode.all("target");
        var second = EventNode.all("target");
        var kept = EventNode.all("kept");
        root.addChild(first);
        root.addChild(second);
        root.addChild(kept);

        root.removeChildren("target");
        assertEquals(1, root.getChildren().size());
        assertTrue(root.getChildren().contains(kept));
        assertNull(first.getParent());
        assertNull(second.getParent());
    }

    @Test
    public void removalIsRecursiveThroughTheWholeTree() {
        var root = EventNode.all("root");
        var middle = EventNode.all("middle");
        var deep = EventNode.all("target");
        AtomicInteger calls = new AtomicInteger();
        deep.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        middle.addChild(deep);
        root.addChild(middle);

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());

        root.removeChildren("target");
        assertTrue(middle.getChildren().isEmpty());
        root.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get(), "The removed node must not be dispatched to anymore");
    }

    @Test
    public void aNameWithoutMatchLeavesTheTreeAlone() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        root.addChild(child);

        root.removeChildren("absent");
        assertEquals(1, root.getChildren().size());
    }

    @Test
    public void anUnrelatedEventTypeDoesNotMatch() {
        var root = EventNode.all("root");
        var typed = EventNode.type("target", EventFilter.ITEM);
        root.addChild(typed);

        // ItemEvent is not assignable from CancellableTest, so the filter rejects the node
        root.removeChildren("target", EventNodeTest.CancellableTest.class);
        assertEquals(1, root.getChildren().size());
    }
}
