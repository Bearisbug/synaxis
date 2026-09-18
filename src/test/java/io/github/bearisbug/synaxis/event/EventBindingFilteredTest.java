package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class EventBindingFilteredTest {

    @Test
    public void theBindingOnlyDeclaresTheMappedEventTypes() {
        var binding = EventBinding.filtered(EventFilter.ITEM, _ -> true)
                .map(EventNodeTest.ItemTestEvent.class, (_, _) -> {
                })
                .build();

        assertEquals(List.of(EventNodeTest.ItemTestEvent.class), List.copyOf(binding.eventTypes()));
        assertNotNull(binding.consumer(EventNodeTest.ItemTestEvent.class));
        assertNull(binding.consumer(EventNodeTest.EventTest.class),
                "An unmapped event type has no consumer");
    }

    @Test
    public void thePredicateGatesTheConsumer() {
        AtomicInteger calls = new AtomicInteger();
        var binding = EventBinding.filtered(EventFilter.ITEM, item -> item.material() == Material.DIAMOND)
                .map(EventNodeTest.ItemTestEvent.class, (_, _) -> calls.incrementAndGet())
                .build();
        var node = EventNode.all("main");
        node.register(binding);

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.STONE)));
        assertEquals(0, calls.get());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());
    }

    @Test
    public void theConsumerReceivesBothTheHandlerAndTheEvent() {
        List<Object> seen = new ArrayList<>();
        var item = ItemStack.of(Material.DIAMOND);
        var event = new EventNodeTest.ItemTestEvent(item);
        var binding = EventBinding.filtered(EventFilter.ITEM, _ -> true)
                .map(EventNodeTest.ItemTestEvent.class, (handler, received) -> {
                    seen.add(handler);
                    seen.add(received);
                })
                .build();

        var node = EventNode.all("main");
        node.register(binding);
        node.call(event);

        assertEquals(List.of(item, event), seen);
    }

    @Test
    public void theSameEventTypeMappedTwiceKeepsOnlyTheLastConsumer() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        var binding = EventBinding.filtered(EventFilter.ITEM, _ -> true)
                .map(EventNodeTest.ItemTestEvent.class, (_, _) -> first.incrementAndGet())
                .map(EventNodeTest.ItemTestEvent.class, (_, _) -> second.incrementAndGet())
                .build();

        var node = EventNode.all("main");
        node.register(binding);
        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));

        assertEquals(0, first.get());
        assertEquals(1, second.get());
        assertEquals(1, binding.eventTypes().size());
    }
}
