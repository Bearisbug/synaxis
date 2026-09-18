package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeBindingLifecycleTest {

    private static EventBinding<ItemEvent> counting(AtomicInteger counter) {
        return EventBinding.filtered(EventFilter.ITEM, _ -> true)
                .map(EventNodeTest.ItemTestEvent.class, (_, _) -> counter.incrementAndGet())
                .build();
    }

    @Test
    public void aRegisteredBindingReceivesTheEventUntilItIsUnregistered() {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        var binding = counting(calls);

        node.register(binding);
        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());

        node.unregister(binding);
        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());
    }

    @Test
    public void registeringTheSameBindingTwiceDoesNotDoubleTheDispatch() {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        var binding = counting(calls);

        node.register(binding);
        node.register(binding);
        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get(), "The binding consumer set must stay a set");
    }

    @Test
    public void aBindingAndAPlainListenerOfTheSameTypeBothRun() {
        var node = EventNode.all("main");
        AtomicInteger binding = new AtomicInteger();
        AtomicInteger listener = new AtomicInteger();
        node.register(counting(binding));
        node.addListener(EventNodeTest.ItemTestEvent.class, _ -> listener.incrementAndGet());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, binding.get());
        assertEquals(1, listener.get());
    }

    @Test
    public void aBindingRegisteredOnAChildIsReachableFromTheRoot() {
        var root = EventNode.all("root");
        var child = EventNode.all("child");
        root.addChild(child);
        root.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));

        AtomicInteger calls = new AtomicInteger();
        child.register(counting(calls));
        assertTrue(root.hasListener(EventNodeTest.ItemTestEvent.class));

        root.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get());
    }
}
