package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeLazyMappedTest {

    @Test
    public void aMappedNodeRegistersOnlyOnceItGetsAChild() {
        var item = ItemStack.of(Material.DIAMOND);
        var root = (EventNodeImpl<Event>) EventNode.all("root");
        var mapped = root.map(item, EventFilter.ITEM);
        assertFalse(registered(root, item));

        AtomicInteger calls = new AtomicInteger();
        var child = EventNode.type("child", EventFilter.ITEM);
        child.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());
        mapped.addChild(child);

        assertTrue(registered(root, item));
        root.call(new EventNodeTest.ItemTestEvent(item));
        assertEquals(1, calls.get());
    }

    @Test
    public void aMappedNodeRegistersOnceItGetsABinding() {
        var item = ItemStack.of(Material.DIAMOND);
        var root = (EventNodeImpl<Event>) EventNode.all("root");
        var mapped = root.map(item, EventFilter.ITEM);
        AtomicInteger calls = new AtomicInteger();

        mapped.register(EventBinding.filtered(EventFilter.ITEM, _ -> true)
                .map(EventNodeTest.ItemTestEvent.class, (_, _) -> calls.incrementAndGet())
                .build());

        assertTrue(registered(root, item));
        root.call(new EventNodeTest.ItemTestEvent(item));
        assertEquals(1, calls.get());
    }

    @Test
    public void aMappedNodeOnlySeesTheEventsOfItsOwner() {
        var diamond = ItemStack.of(Material.DIAMOND);
        var stone = ItemStack.of(Material.STONE);
        var root = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        // The holder only references a mapped node weakly, the caller has to keep it alive
        var mapped = root.map(diamond, EventFilter.ITEM);
        mapped.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        root.call(new EventNodeTest.ItemTestEvent(stone));
        assertEquals(0, calls.get());

        root.call(new EventNodeTest.ItemTestEvent(diamond));
        assertEquals(1, calls.get());
    }

    @Test
    public void aMappedNodeRefusesToBeRemappedOnAnotherOwner() {
        var item = ItemStack.of(Material.DIAMOND);
        var root = EventNode.all("root");
        var mapped = root.map(item, EventFilter.ITEM);

        assertSame(mapped, mapped.map(item, EventFilter.ITEM));
        assertThrows(IllegalArgumentException.class, () -> mapped.map(ItemStack.of(Material.STONE), EventFilter.ITEM));
    }

    @Test
    public void unmappingAnUnknownOwnerIsANoOp() {
        var root = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        var item = ItemStack.of(Material.DIAMOND);
        var mapped = root.map(item, EventFilter.ITEM);
        mapped.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        root.unmap(ItemStack.of(Material.STONE));
        root.call(new EventNodeTest.ItemTestEvent(item));
        assertEquals(1, calls.get(), "Unmapping a stranger must not detach the real mapping");
    }

    // The registered nodes are guarded by the global lock, and a read of a WeakHashMap mutates it
    private static boolean registered(EventNodeImpl<?> holder, Object owner) {
        synchronized (EventNodeImpl.GLOBAL_CHILD_LOCK) {
            return holder.registeredMappedNode.containsKey(owner);
        }
    }
}
