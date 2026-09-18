package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeHandleTest {

    @Test
    public void aHandleIsCachedPerEventType() {
        var node = EventNode.all("main");
        var handle = node.getHandle(EventNodeTest.EventTest.class);
        assertSame(handle, node.getHandle(EventNodeTest.EventTest.class));
        assertNotSame(handle, node.getHandle(EventNodeTest.CancellableTest.class));
    }

    @Test
    public void aHandleTakenBeforeAnyListenerStaysValid() {
        var node = EventNode.all("main");
        var handle = node.getHandle(EventNodeTest.EventTest.class);
        assertFalse(handle.hasListener());

        AtomicInteger calls = new AtomicInteger();
        node.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        assertTrue(handle.hasListener());

        handle.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }

    @Test
    public void handlesOfDifferentNodesAreIndependent() {
        var first = EventNode.all("first");
        var second = EventNode.all("second");
        first.addListener(EventNodeTest.EventTest.class, _ -> {
        });

        assertTrue(first.getHandle(EventNodeTest.EventTest.class).hasListener());
        assertFalse(second.getHandle(EventNodeTest.EventTest.class).hasListener());
    }

    @Test
    public void aHandleOfAnUnrelatedTypeStaysEmpty() {
        var node = EventNode.all("main");
        node.addListener(EventNodeTest.EventTest.class, _ -> {
        });
        assertFalse(node.hasListener(EventNodeTest.CancellableTest.class));
    }

    @Test
    public void callingAnEmptyHandleIsANoOp() {
        var node = EventNode.all("main");
        var handle = node.getHandle(EventNodeTest.EventTest.class);
        handle.call(new EventNodeTest.EventTest());
        assertFalse(handle.hasListener());
    }
}
