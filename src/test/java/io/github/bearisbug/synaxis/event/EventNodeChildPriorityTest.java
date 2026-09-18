package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventNodeChildPriorityTest {

    private static EventNode<Event> recording(String name, int priority, List<String> sink) {
        var node = EventNode.all(name).setPriority(priority);
        node.addListener(EventNodeTest.EventTest.class, _ -> sink.add(name));
        return node;
    }

    @Test
    public void childrenRunFromTheLowestPriorityToTheHighest() {
        List<String> order = new ArrayList<>();
        var root = EventNode.all("root");
        root.addChild(recording("high", 10, order));
        root.addChild(recording("low", 1, order));
        root.addChild(recording("mid", 5, order));

        root.call(new EventNodeTest.EventTest());
        assertEquals(List.of("low", "mid", "high"), order);
    }

    @Test
    public void aPriorityChangedAfterTheFirstDispatchTakesEffect() {
        // The parent compiles its children into a sorted dispatch order and caches it, so a later
        // setPriority has to invalidate that cache
        List<String> order = new ArrayList<>();
        var root = EventNode.all("root");
        var first = recording("first", 1, order);
        var second = recording("second", 2, order);
        root.addChild(first);
        root.addChild(second);

        root.call(new EventNodeTest.EventTest());
        assertEquals(List.of("first", "second"), order);

        order.clear();
        first.setPriority(3);
        root.call(new EventNodeTest.EventTest());
        assertEquals(List.of("second", "first"), order);
    }

    @Test
    public void aPriorityChangeReachesAGrandparentDispatch() {
        List<String> order = new ArrayList<>();
        var root = EventNode.all("root");
        var middle = EventNode.all("middle");
        var first = recording("first", 1, order);
        var second = recording("second", 2, order);
        middle.addChild(first);
        middle.addChild(second);
        root.addChild(middle);

        root.call(new EventNodeTest.EventTest());
        assertEquals(List.of("first", "second"), order);

        order.clear();
        second.setPriority(0);
        root.call(new EventNodeTest.EventTest());
        assertEquals(List.of("second", "first"), order);
    }

    @Test
    public void setPriorityIsChainableAndReadable() {
        var node = EventNode.all("node");
        assertEquals(0, node.getPriority(), "The default priority is zero");
        assertEquals(node, node.setPriority(7));
        assertEquals(7, node.getPriority());
    }
}
