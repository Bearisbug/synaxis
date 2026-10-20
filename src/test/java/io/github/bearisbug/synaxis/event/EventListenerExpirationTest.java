package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    public void expireCountHoldsAgainstAConcurrentRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var listener = EventListener.builder(Valued.class)
                .expireCount(1)
                .handler(event -> {
                    calls.incrementAndGet();
                    if (event.value() != 1) return;
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .build();

        // The first run is inside its handler while the second one comes in
        var first = new FutureTask<>(() -> listener.run(new Valued(1)));
        Thread.ofPlatform().start(first);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(EventListener.Result.EXPIRED, listener.run(new Valued(2)));
            assertEquals(1, calls.get(), "The second run found no slot left");
        } finally {
            release.countDown();
        }
        assertEquals(EventListener.Result.EXPIRED, first.get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    public void aThrowingHandlerUsesUpItsRun() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.builder(Valued.class)
                .expireCount(1)
                .handler(_ -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                })
                .build();

        assertThrows(IllegalStateException.class, () -> listener.run(new Valued(0)));
        assertEquals(EventListener.Result.EXPIRED, listener.run(new Valued(0)));
        assertEquals(1, calls.get(), "The handler runs at most expireCount times");
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

    @Test
    public void expireCountRefusesLessThanOneRun() {
        var builder = EventListener.builder(Valued.class);
        assertEquals("expireCount must be at least 1, was 0",
                assertThrows(IllegalArgumentException.class, () -> builder.expireCount(0)).getMessage());
        assertEquals("expireCount must be at least 1, was -1",
                assertThrows(IllegalArgumentException.class, () -> builder.expireCount(-1)).getMessage());
    }
}
