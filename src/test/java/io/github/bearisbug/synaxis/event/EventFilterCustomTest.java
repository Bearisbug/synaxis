package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class EventFilterCustomTest {

    record Owned(String owner) implements Event {
    }

    @Test
    public void aCustomFilterReportsItsTypesAndHandler() {
        var filter = EventFilter.from(Owned.class, String.class, Owned::owner);
        assertSame(Owned.class, filter.eventType());
        assertSame(String.class, filter.handlerType());
        assertEquals("bob", filter.getHandler(new Owned("bob")));
    }

    @Test
    public void aFilterWithoutGetterAlwaysReportsNoHandler() {
        var filter = EventFilter.from(Owned.class, null, null);
        assertNull(filter.handlerType());
        assertNull(filter.getHandler(new Owned("bob")));
    }

    @Test
    public void aNodeBuiltOnACustomFilterRoutesOnTheHandler() {
        var filter = EventFilter.from(Owned.class, String.class, Owned::owner);
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.value("bob-only", filter, "bob"::equals);
        node.addListener(Owned.class, _ -> calls.incrementAndGet());

        node.call(new Owned("alice"));
        assertEquals(0, calls.get());

        node.call(new Owned("bob"));
        assertEquals(1, calls.get());
    }

    @Test
    public void aCustomFilterCanBeMappedOnItsHandlerValue() {
        var filter = EventFilter.from(Owned.class, String.class, Owned::owner);
        var root = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        // The holder only references a mapped node weakly, the caller has to keep it alive
        var mapped = root.map("bob", filter);
        mapped.addListener(Owned.class, _ -> calls.incrementAndGet());

        root.call(new Owned("alice"));
        assertEquals(0, calls.get());

        root.call(new Owned("bob"));
        assertEquals(1, calls.get(), "A mapped node matches its owner by equality");
    }
}
