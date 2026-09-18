package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class EventNodePredicateTest {

    @Test
    public void anEventPredicateSeesTheEventItself() {
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.event("diamond", EventFilter.ITEM,
                event -> event.getItemStack().material() == Material.DIAMOND);
        node.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.STONE)));
        assertEquals(0, calls.get());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());
    }

    @Test
    public void aValuePredicateSeesTheFilterHandler() {
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.value("diamond", EventFilter.ITEM,
                item -> item.material() == Material.DIAMOND);
        node.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.STONE)));
        assertEquals(0, calls.get());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());
    }

    @Test
    public void aBiPredicateSeesBothTheEventAndTheHandler() {
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.type("both", EventFilter.ITEM,
                (event, item) -> event.getItemStack() == item && item.material() == Material.DIAMOND);
        node.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());
    }

    @Test
    public void aRejectingPredicateAlsoStopsTheChildren() {
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.value("diamond", EventFilter.ITEM,
                item -> item.material() == Material.DIAMOND);
        var child = EventNode.type("child", EventFilter.ITEM);
        child.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());
        node.addChild(child);

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.STONE)));
        assertEquals(0, calls.get(), "The parent predicate must gate the whole subtree");

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());
    }

    @Test
    public void aTypedNodeCarriesTheFilterEventType() {
        var node = EventNode.type("typed", EventFilter.ITEM);
        assertSame(EventFilter.ITEM.eventType(), node.getEventType());
        assertEquals("typed", node.getName());
    }
}
