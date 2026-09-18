package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventNodeReentrantCallTest {

    record Inner(int depth) implements Event {
    }

    @Test
    public void aListenerMayCallAnotherEventOnTheSameNode() {
        var node = EventNode.all("main");
        List<String> order = new ArrayList<>();
        node.addListener(Inner.class, event -> order.add("inner" + event.depth()));
        node.addListener(EventNodeTest.EventTest.class, _ -> {
            order.add("outer-start");
            node.call(new Inner(1));
            order.add("outer-end");
        });

        node.call(new EventNodeTest.EventTest());
        assertEquals(List.of("outer-start", "inner1", "outer-end"), order);
    }

    @Test
    public void aListenerMayRecurseOnItsOwnEventType() {
        var node = EventNode.all("main");
        AtomicInteger depth = new AtomicInteger();
        node.addListener(Inner.class, event -> {
            depth.incrementAndGet();
            if (event.depth() < 3) node.call(new Inner(event.depth() + 1));
        });

        node.call(new Inner(1));
        assertEquals(3, depth.get());
    }

    @Test
    public void aListenerAddedDuringADispatchOnlyRunsOnTheNextCall() {
        var node = EventNode.all("main");
        AtomicInteger added = new AtomicInteger();
        AtomicInteger outer = new AtomicInteger();
        node.addListener(EventNodeTest.EventTest.class, _ -> {
            outer.incrementAndGet();
            if (outer.get() == 1) {
                node.addListener(EventNodeTest.EventTest.class, _ -> added.incrementAndGet());
            }
        });

        node.call(new EventNodeTest.EventTest());
        assertEquals(0, added.get(), "The running dispatch was compiled before the new listener");

        node.call(new EventNodeTest.EventTest());
        assertEquals(1, added.get());
    }

    @Test
    public void aListenerRemovedDuringADispatchStillFinishesTheRunningCall() {
        var node = EventNode.all("main");
        AtomicInteger second = new AtomicInteger();
        var victim = EventListener.of(EventNodeTest.EventTest.class, _ -> second.incrementAndGet());
        node.addListener(EventNodeTest.EventTest.class, _ -> node.removeListener(victim));
        node.addListener(victim);

        node.call(new EventNodeTest.EventTest());
        assertEquals(1, second.get(), "The compiled dispatch of the running call is a snapshot");

        node.call(new EventNodeTest.EventTest());
        assertEquals(1, second.get());
    }
}
