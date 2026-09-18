package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeEventTypeFilterTest {

    static class SubEvent extends EventNodeTest.EventTest {
    }

    @Test
    public void aTypedChildOnlyReceivesItsOwnEventFamily() {
        var process = ServerProcess.of();
        var root = EventNode.all("root");
        var itemNode = EventNode.type("items", EventFilter.ITEM);
        var entityNode = EventNode.type("entities", EventFilter.ENTITY);
        AtomicInteger items = new AtomicInteger();
        AtomicInteger entities = new AtomicInteger();
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> items.incrementAndGet());
        entityNode.addListener(EventNodeTest.EntityTestEvent.class, _ -> entities.incrementAndGet());
        root.addChild(itemNode);
        root.addChild(entityNode);

        root.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, items.get());
        assertEquals(0, entities.get());

        root.call(new EventNodeTest.EntityTestEvent(new Entity(process, EntityType.ZOMBIE)));
        assertEquals(1, items.get());
        assertEquals(1, entities.get());
    }

    @Test
    public void anItemOnlySubtreeIsExcludedFromAPlainEventDispatch() {
        var root = EventNode.all("root");
        var itemNode = EventNode.type("items", EventFilter.ITEM);
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> {
        });
        root.addChild(itemNode);

        assertTrue(root.hasListener(EventNodeTest.ItemTestEvent.class));
        assertFalse(root.hasListener(EventNodeTest.EventTest.class),
                "A plain event must not be routed into an item-only subtree");
    }

    @Test
    public void aListenerOfASuperTypeDoesNotCatchItsSubTypes() {
        // Dispatch is keyed on the exact runtime class unless the event is a RecursiveEvent
        var node = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        node.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());

        node.call(new SubEvent());
        assertEquals(0, calls.get());

        node.call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }
}
