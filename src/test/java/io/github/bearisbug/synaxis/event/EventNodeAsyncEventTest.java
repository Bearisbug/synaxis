package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.event.trait.AsyncEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventNodeAsyncEventTest {

    static class AsyncTest implements AsyncEvent {
    }

    @Test
    public void anAsyncEventDispatchedFromAVirtualThreadReachesItsListener() throws InterruptedException {
        var node = EventNode.all("main");
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean virtual = new AtomicBoolean();
        node.addListener(AsyncTest.class, _ -> {
            calls.incrementAndGet();
            virtual.set(Thread.currentThread().isVirtual());
        });

        var thread = Thread.ofVirtual().start(() -> node.call(new AsyncTest()));
        thread.join();

        assertEquals(1, calls.get());
        assertTrue(virtual.get());
    }

    @Test
    public void anAsyncEventDispatchedFromAPlatformThreadTripsTheAssertion() {
        // The engine guarantees AsyncEvent runs on a virtual thread; the tests run with assertions
        // enabled so the violation has to be loud instead of silent
        var node = EventNode.all("main");
        node.addListener(AsyncTest.class, _ -> {
        });

        assertThrows(AssertionError.class, () -> node.call(new AsyncTest()),
                "Dispatching an AsyncEvent off a virtual thread must not pass silently");
    }
}
