package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventListenerCancelPolicyTest {

    @Test
    public void cancelledEventsAreDroppedByDefault() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.builder(EventNodeTest.CancellableTest.class)
                .handler(_ -> calls.incrementAndGet())
                .build();

        var event = new EventNodeTest.CancellableTest();
        event.setCancelled(true);
        assertEquals(EventListener.Result.INVALID, listener.run(event));
        assertEquals(0, calls.get());
    }

    @Test
    public void ignoreCancelledFalseLetsTheHandlerSeeACancelledEvent() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.builder(EventNodeTest.CancellableTest.class)
                .ignoreCancelled(false)
                .handler(_ -> calls.incrementAndGet())
                .build();

        var event = new EventNodeTest.CancellableTest();
        event.setCancelled(true);
        assertEquals(EventListener.Result.SUCCESS, listener.run(event));
        assertEquals(1, calls.get());
    }

    @Test
    public void aLaterListenerOfTheSameNodeSeesTheCancellationOfTheFirstOne() {
        var node = EventNode.all("main");
        AtomicInteger second = new AtomicInteger();
        node.addListener(EventNodeTest.CancellableTest.class, event -> event.setCancelled(true));
        node.addListener(EventNodeTest.CancellableTest.class, _ -> second.incrementAndGet());

        node.call(new EventNodeTest.CancellableTest());
        assertEquals(0, second.get(), "The second listener must not run once the event is cancelled");
    }
}
