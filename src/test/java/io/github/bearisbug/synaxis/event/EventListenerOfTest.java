package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.RecursiveEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class EventListenerOfTest {

    static class RecursiveCancellable implements RecursiveEvent, CancellableEvent {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            this.cancelled = cancel;
        }
    }

    @Test
    public void plainEventAlwaysReachesTheHandler() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.of(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        assertSame(EventNodeTest.EventTest.class, listener.eventType());
        assertEquals(EventListener.Result.SUCCESS, listener.run(new EventNodeTest.EventTest()));
        assertEquals(1, calls.get());
    }

    @Test
    public void cancelledEventIsInvalidAndSkipsTheHandler() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.of(EventNodeTest.CancellableTest.class, _ -> calls.incrementAndGet());
        var event = new EventNodeTest.CancellableTest();

        assertEquals(EventListener.Result.SUCCESS, listener.run(event));
        assertEquals(1, calls.get());

        event.setCancelled(true);
        assertEquals(EventListener.Result.INVALID, listener.run(event));
        assertEquals(1, calls.get(), "A cancelled event must not reach the handler");
    }

    @Test
    public void recursiveEventWithoutCancellationStillRuns() {
        // The recursive branch of EventListener#of also wraps the cancellation check, a recursive
        // event which is not cancellable must not be dropped by it.
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.of(EventNodeTest.Recursive1.class, _ -> calls.incrementAndGet());
        assertEquals(EventListener.Result.SUCCESS, listener.run(new EventNodeTest.Recursive1()));
        assertEquals(1, calls.get());
    }

    @Test
    public void recursiveAndCancellableEventIsDroppedWhenCancelled() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.of(RecursiveCancellable.class, _ -> calls.incrementAndGet());
        var event = new RecursiveCancellable();
        event.setCancelled(true);
        assertEquals(EventListener.Result.INVALID, listener.run(event));
        assertEquals(0, calls.get());
    }
}
