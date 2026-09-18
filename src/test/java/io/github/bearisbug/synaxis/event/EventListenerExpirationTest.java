package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventListenerExpirationTest {

    record Valued(int value) implements Event {
    }

    @Test
    public void expireCountReportsExpiredOnItsLastRun() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.builder(Valued.class)
                .expireCount(2)
                .handler(_ -> calls.incrementAndGet())
                .build();

        assertEquals(EventListener.Result.SUCCESS, listener.run(new Valued(0)));
        assertEquals(EventListener.Result.EXPIRED, listener.run(new Valued(0)));
        assertEquals(2, calls.get(), "The handler runs on the expiring call too");
    }

    @Test
    public void expireCountZeroNeverExpires() {
        var listener = EventListener.builder(Valued.class).build();
        for (int i = 0; i < 5; i++) {
            assertEquals(EventListener.Result.SUCCESS, listener.run(new Valued(i)));
        }
    }

    @Test
    public void expireWhenIsCheckedBeforeTheHandler() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.builder(Valued.class)
                .expireWhen(event -> event.value() < 0)
                .handler(_ -> calls.incrementAndGet())
                .build();

        assertEquals(EventListener.Result.SUCCESS, listener.run(new Valued(1)));
        assertEquals(EventListener.Result.EXPIRED, listener.run(new Valued(-1)));
        assertEquals(1, calls.get(), "The expiring event must not reach the handler");
    }

    @Test
    public void cancellationIsCheckedBeforeExpiration() {
        var listener = EventListener.builder(EventNodeTest.CancellableTest.class)
                .expireWhen(_ -> true)
                .build();

        var event = new EventNodeTest.CancellableTest();
        event.setCancelled(true);
        assertEquals(EventListener.Result.INVALID, listener.run(event),
                "A cancelled event must not burn the expiration");
    }
}
