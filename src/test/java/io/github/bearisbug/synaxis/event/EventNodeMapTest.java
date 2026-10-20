package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.bearisbug.synaxis.testing.TestUtils.waitUntilCleared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeMapTest {

    @Test
    public void uniqueMapping() {
        var item = ItemStack.of(Material.DIAMOND);
        var node = EventNode.all("main");
        var itemNode1 = node.map(item, EventFilter.ITEM);
        var itemNode2 = node.map(item, EventFilter.ITEM);
        assertNotNull(itemNode1);
        assertSame(itemNode1, itemNode2);

        // Node should still keep track of the mapping until GCed
        // This is to ensure that we do not end up with multiple nodes theoretically mapping the same object
        node.unmap(item);
        assertSame(itemNode1, node.map(item, EventFilter.ITEM));
    }

    @Test
    public void lazyRegistration() {
        var item = ItemStack.of(Material.DIAMOND);
        var node = (EventNodeImpl<Event>) EventNode.all("main");
        var itemNode = node.map(item, EventFilter.ITEM);
        assertFalse(registered(node, item));
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> {
        });
        assertTrue(registered(node, item));
    }

    @Test
    public void secondMap() {
        var item = ItemStack.of(Material.DIAMOND);
        var node = (EventNodeImpl<Event>) EventNode.all("main");
        var itemNode = node.map(item, EventFilter.ITEM);
        assertSame(itemNode, itemNode.map(item, EventFilter.ITEM));
        assertThrows(Exception.class, () -> itemNode.map(ItemStack.AIR, EventFilter.ITEM));
    }

    @Test
    public void map() {
        var item = ItemStack.of(Material.DIAMOND);
        var node = EventNode.all("main");

        AtomicBoolean result = new AtomicBoolean(false);
        var itemNode = node.map(item, EventFilter.ITEM);

        assertFalse(node.hasListener(EventNodeTest.ItemTestEvent.class));
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> result.set(true));
        assertTrue(node.hasListener(EventNodeTest.ItemTestEvent.class));

        node.call(new EventNodeTest.ItemTestEvent(item));
        assertTrue(result.get());

        result.set(false);
        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.GOLD_INGOT)));
        assertFalse(result.get());

        result.set(false);
        node.unmap(item);
        node.call(new EventNodeTest.ItemTestEvent(item));
        assertFalse(result.get());
    }

    @Test
    public void entityLocal() {
        var process = ServerProcess.of();
        var node = process.eventHandler();
        var entity = new Entity(process, EntityType.ZOMBIE);

        AtomicBoolean result = new AtomicBoolean(false);
        var listener = EventListener.of(EventNodeTest.EntityTestEvent.class, _ -> result.set(true));

        var handle = node.getHandle(EventNodeTest.EntityTestEvent.class);
        assertFalse(handle.hasListener());
        entity.eventNode().addListener(listener);
        assertTrue(handle.hasListener());

        assertFalse(result.get());

        handle.call(new EventNodeTest.EntityTestEvent(entity));
        assertTrue(result.get());

        result.set(false);
        entity.eventNode().removeListener(listener);

        handle.call(new EventNodeTest.EntityTestEvent(entity));
        assertFalse(result.get());
    }

    @Test
    public void ownerGC() {
        // Ensure that the mapped object gets GCed
        var item = ItemStack.of(Material.DIAMOND);
        var node = EventNode.all("main");
        var itemNode = node.map(item, EventFilter.ITEM);
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> {
        });
        node.call(new EventNodeTest.ItemTestEvent(item));

        var ref = new WeakReference<>(item);
        //noinspection UnusedAssignment
        item = null;
        waitUntilCleared(ref);
    }

    @Test
    public void remapAfterUnmap() {
        var item = ItemStack.of(Material.DIAMOND);
        var node = EventNode.all("main");
        AtomicBoolean result = new AtomicBoolean(false);

        var itemNode = node.map(item, EventFilter.ITEM);
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> result.set(true));
        node.call(new EventNodeTest.ItemTestEvent(item));
        assertTrue(result.get());

        node.unmap(item);
        result.set(false);
        node.call(new EventNodeTest.ItemTestEvent(item));
        assertFalse(result.get(), "An unmapped node should not receive events anymore");

        // The cache still returns the very same node, adding a listener to it must map it again
        assertSame(itemNode, node.map(item, EventFilter.ITEM));
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> result.set(true));
        node.call(new EventNodeTest.ItemTestEvent(item));
        assertTrue(result.get(), "A listener added after unmap should map the node again");
    }

    @Test
    public void mapAfterNodeCollected() {
        var item = ItemStack.of(Material.DIAMOND);
        var node = EventNode.all("main");

        var ref = new WeakReference<>(node.map(item, EventFilter.ITEM));
        waitUntilCleared(ref);

        // The stale cache entry must be replaced, otherwise every call creates yet another node
        var first = node.map(item, EventFilter.ITEM);
        var second = node.map(item, EventFilter.ITEM);
        assertSame(first, second);
    }

    @Test
    public void listenAfterRegisteredNodeCollected() {
        var item = ItemStack.of(Material.DIAMOND);
        var node = EventNode.all("main");
        AtomicBoolean result = new AtomicBoolean(false);

        // Register a first node, then let it be collected while the owner stays alive
        var first = node.map(item, EventFilter.ITEM);
        first.addListener(EventNodeTest.ItemTestEvent.class, _ -> {
        });
        var ref = new WeakReference<>(first);
        //noinspection UnusedAssignment
        first = null;
        waitUntilCleared(ref);

        var second = node.map(item, EventFilter.ITEM);
        second.addListener(EventNodeTest.ItemTestEvent.class, _ -> result.set(true));
        node.call(new EventNodeTest.ItemTestEvent(item));
        assertTrue(result.get(), "A node mapped again after the previous one was collected must receive events");
    }

    @Test
    public void mappedOnlyChildAdded() {
        var item = ItemStack.of(Material.DIAMOND);
        var parent = EventNode.all("parent");
        var child = EventNode.all("child");
        AtomicBoolean result = new AtomicBoolean(false);
        var itemNode = child.map(item, EventFilter.ITEM);
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> result.set(true));

        // Compile and cache the parent handle before the subtree gets attached
        parent.call(new EventNodeTest.ItemTestEvent(item));
        assertFalse(result.get());

        parent.addChild(child);
        parent.call(new EventNodeTest.ItemTestEvent(item));
        assertTrue(result.get(), "A child whose only listeners are mapped must receive events once added");
    }

    @Test
    public void mappedOnlyChildRemoved() {
        var item = ItemStack.of(Material.DIAMOND);
        var parent = EventNode.all("parent");
        var child = EventNode.all("child");
        AtomicBoolean result = new AtomicBoolean(false);
        var itemNode = child.map(item, EventFilter.ITEM);
        itemNode.addListener(EventNodeTest.ItemTestEvent.class, _ -> result.set(true));

        parent.addChild(child);
        parent.call(new EventNodeTest.ItemTestEvent(item));
        assertTrue(result.get());

        result.set(false);
        parent.removeChild(child);
        parent.call(new EventNodeTest.ItemTestEvent(item));
        assertFalse(result.get(), "A removed child whose only listeners are mapped must not receive events anymore");
    }

    @Test
    public void aMappedOwnerIsNamedOnlyWhenItsNodeIsBuilt() {
        // A node takes the toString() of its owner as its name
        final class Owner {
            int names;

            @Override
            public String toString() {
                names++;
                return "owner";
            }
        }
        var owner = new Owner();
        var filter = EventFilter.from(EventNodeTest.EventTest.class, Owner.class, _ -> owner);
        var node = EventNode.all("main");

        var first = node.map(owner, filter);
        assertSame(first, node.map(owner, filter));
        assertEquals(1, owner.names, "the second map returns the cached node without building another one");
    }

    // The registered nodes are guarded by the global lock, and a read of a WeakHashMap mutates it
    private static boolean registered(EventNodeImpl<?> holder, Object owner) {
        synchronized (EventNodeImpl.GLOBAL_CHILD_LOCK) {
            return holder.registeredMappedNode.containsKey(owner);
        }
    }
}
