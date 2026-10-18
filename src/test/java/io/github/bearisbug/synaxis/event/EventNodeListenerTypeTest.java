package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;
import io.github.bearisbug.synaxis.event.trait.RecursiveEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listeners are keyed on the exact runtime class of the event, so a type no event is dispatched as
 * is refused when the listener is added instead of never being called.
 */
public class EventNodeListenerTypeTest {

    abstract static class AbstractEvent implements Event {
    }

    abstract static class AbstractRecursive implements RecursiveEvent {
    }

    static final class ConcreteRecursive extends AbstractRecursive {
    }

    @Test
    public void anInterfaceOrAnAbstractClassIsRefusedAsAListenerType() {
        var node = EventNode.all("main");
        var thrown = assertThrows(IllegalArgumentException.class, () -> node.addListener(PlayerEvent.class, _ -> {
        }));
        assertTrue(thrown.getMessage().contains(PlayerEvent.class.getName()), thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> node.addListener(EntityInstanceEvent.class, _ -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> node.addListener(AbstractEvent.class, _ -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> node.register(EventBinding.filtered(EventFilter.ITEM, _ -> true)
                .map(ItemEvent.class, (_, _) -> {
                })
                .build()));
        assertFalse(node.hasListener(PlayerEvent.class), "nothing was registered");
    }

    @Test
    public void anAbstractRecursiveClassHearsItsSubclasses() {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        node.addListener(AbstractRecursive.class, _ -> calls.incrementAndGet());

        node.call(new ConcreteRecursive());
        assertEquals(1, calls.get());
    }

    @Test
    public void aRefusedTypeLeavesAMappedNodeUnregistered() {
        var item = ItemStack.of(Material.DIAMOND);
        var root = (EventNodeImpl<Event>) EventNode.all("root");
        var mapped = root.map(item, EventFilter.ITEM);

        assertThrows(IllegalArgumentException.class, () -> mapped.addListener(ItemEvent.class, _ -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> mapped.register(EventBinding.filtered(EventFilter.ITEM, _ -> true)
                .map(ItemEvent.class, (_, _) -> {
                })
                .build()));
        synchronized (EventNodeImpl.GLOBAL_CHILD_LOCK) {
            assertFalse(root.registeredMappedNode.containsKey(item));
        }
    }
}
