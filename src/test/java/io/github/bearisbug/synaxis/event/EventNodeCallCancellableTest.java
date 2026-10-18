package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeCallCancellableTest {

    @Test
    public void theCallbackRunsWhenNoListenerCancels() {
        var node = EventNode.all("main");
        AtomicInteger listener = new AtomicInteger();
        AtomicInteger callback = new AtomicInteger();
        node.addListener(EventNodeTest.CancellableTest.class, _ -> listener.incrementAndGet());

        node.callCancellable(new EventNodeTest.CancellableTest(), callback::incrementAndGet);
        assertEquals(1, listener.get());
        assertEquals(1, callback.get());
    }

    @Test
    public void theCallbackIsSkippedOnceAListenerCancels() {
        var node = EventNode.all("main");
        AtomicInteger callback = new AtomicInteger();
        node.addListener(EventNodeTest.CancellableTest.class, event -> event.setCancelled(true));

        var event = new EventNodeTest.CancellableTest();
        node.callCancellable(event, callback::incrementAndGet);
        assertTrue(event.isCancelled());
        assertEquals(0, callback.get());
    }

    @Test
    public void aListenerThrowingBeforeTheCancelLetsTheCallbackRun() {
        var node = EventNode.all("main");
        AtomicInteger callback = new AtomicInteger();
        node.addListener(EventNodeTest.CancellableTest.class, _ -> {
            throw new IllegalStateException("boom");
        });
        node.addListener(EventNodeTest.CancellableTest.class, event -> event.setCancelled(true));

        var event = new EventNodeTest.CancellableTest();
        node.callCancellable(event, callback::incrementAndGet);
        assertFalse(event.isCancelled(), "The dispatch was abandoned before the cancelling listener");
        assertEquals(1, callback.get(), "Only the cancel state at the end of the dispatch counts");
    }

    @Test
    public void aNonCancellableEventAlwaysRunsTheCallback() {
        var node = EventNode.all("main");
        AtomicInteger callback = new AtomicInteger();
        node.callCancellable(new EventNodeTest.EventTest(), callback::incrementAndGet);
        assertEquals(1, callback.get());
    }

    @Test
    public void anAlreadyCancelledEventSkipsBothTheListenersAndTheCallback() {
        var node = EventNode.all("main");
        AtomicInteger listener = new AtomicInteger();
        AtomicInteger callback = new AtomicInteger();
        node.addListener(EventNodeTest.CancellableTest.class, _ -> listener.incrementAndGet());

        var event = new EventNodeTest.CancellableTest();
        event.setCancelled(true);
        node.callCancellable(event, callback::incrementAndGet);
        assertEquals(0, listener.get());
        assertEquals(0, callback.get());
    }
}
