package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeExpiredListenerTest {

    @Test
    public void anExpiredListenerIsDroppedFromTheNode() {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        node.addListener(EventListener.builder(EventNodeTest.EventTest.class)
                .expireCount(1)
                .handler(_ -> calls.incrementAndGet())
                .build());

        node.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
        assertFalse(node.hasListener(EventNodeTest.EventTest.class),
                "An expired listener must not stay registered");

        node.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }

    @Test
    public void expirationOnlyRemovesTheExpiredListener() {
        var node = EventNode.all("main");
        AtomicInteger expiring = new AtomicInteger();
        AtomicInteger permanent = new AtomicInteger();
        node.addListener(EventListener.builder(EventNodeTest.EventTest.class)
                .expireCount(1)
                .handler(_ -> expiring.incrementAndGet())
                .build());
        node.addListener(EventNodeTest.EventTest.class, _ -> permanent.incrementAndGet());

        node.call(new EventNodeTest.EventTest());
        node.call(new EventNodeTest.EventTest());

        assertEquals(1, expiring.get());
        assertEquals(2, permanent.get());
        assertTrue(node.hasListener(EventNodeTest.EventTest.class));
    }

    @Test
    public void anExpiredListenerOfAChildIsDroppedFromTheRootDispatch() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        root.addChild(child);
        AtomicInteger calls = new AtomicInteger();
        child.addListener(EventListener.builder(EventNodeTest.EventTest.class)
                .expireWhen(_ -> calls.get() >= 2)
                .handler(_ -> calls.incrementAndGet())
                .build());

        root.call(new EventNodeTest.EventTest());
        root.call(new EventNodeTest.EventTest());
        assertEquals(2, calls.get());

        root.call(new EventNodeTest.EventTest());
        assertEquals(2, calls.get());
        assertFalse(root.hasListener(EventNodeTest.EventTest.class));
    }
}
