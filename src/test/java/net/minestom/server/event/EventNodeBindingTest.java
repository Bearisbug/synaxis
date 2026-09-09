package net.minestom.server.event;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeBindingTest {

    record KnownEvent() implements Event {
    }

    record UnknownEvent() implements Event {
    }

    private static final class TestBinding implements EventBinding<Event> {
        private final Consumer<Event> consumer;
        private Collection<Class<? extends Event>> eventTypes;

        private TestBinding(Consumer<Event> consumer, Collection<Class<? extends Event>> eventTypes) {
            this.consumer = consumer;
            this.eventTypes = eventTypes;
        }

        @Override
        public Collection<Class<? extends Event>> eventTypes() {
            return eventTypes;
        }

        @Override
        public Consumer<Event> consumer(Class<? extends Event> eventType) {
            return consumer;
        }
    }

    @Test
    public void unregisterSkipsUnknownEventTypes() {
        var node = EventNode.all("main");
        AtomicBoolean result = new AtomicBoolean(false);
        var binding = new TestBinding(_ -> result.set(true), List.of(KnownEvent.class));
        node.register(binding);

        node.call(new KnownEvent());
        assertTrue(result.get());

        // An event type this node never registered must not abort the whole unregister
        binding.eventTypes = List.of(UnknownEvent.class, KnownEvent.class);
        node.unregister(binding);

        result.set(false);
        node.call(new KnownEvent());
        assertFalse(result.get(), "The binding consumer was left registered");
    }
}
