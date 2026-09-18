package io.github.bearisbug.synaxis.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class EventListenerFilterTest {

    record Valued(int value) implements Event {
    }

    @Test
    public void everyFilterMustPassBeforeTheHandlerRuns() {
        AtomicInteger calls = new AtomicInteger();
        var listener = EventListener.builder(Valued.class)
                .filter(event -> event.value() > 0)
                .filter(event -> event.value() % 2 == 0)
                .handler(_ -> calls.incrementAndGet())
                .build();

        assertEquals(EventListener.Result.INVALID, listener.run(new Valued(-2)));
        assertEquals(EventListener.Result.INVALID, listener.run(new Valued(3)));
        assertEquals(0, calls.get());

        assertEquals(EventListener.Result.SUCCESS, listener.run(new Valued(4)));
        assertEquals(1, calls.get());
    }

    @Test
    public void aRejectingFilterShortCircuitsTheFollowingOnes() {
        List<String> evaluated = new ArrayList<>();
        var listener = EventListener.builder(Valued.class)
                .filter(_ -> {
                    evaluated.add("first");
                    return false;
                })
                .filter(_ -> {
                    evaluated.add("second");
                    return true;
                })
                .handler(_ -> evaluated.add("handler"))
                .build();

        assertEquals(EventListener.Result.INVALID, listener.run(new Valued(1)));
        assertEquals(List.of("first"), evaluated);
    }

    @Test
    public void aListenerWithoutHandlerStillReportsSuccess() {
        var listener = EventListener.builder(Valued.class).build();
        assertSame(Valued.class, listener.eventType());
        assertEquals(EventListener.Result.SUCCESS, listener.run(new Valued(1)));
    }

    @Test
    public void filtersAreSnapshotAtBuildTime() {
        var builder = EventListener.builder(Valued.class).filter(event -> event.value() > 0);
        var listener = builder.build();
        // Mutating the builder afterwards must not reach the already built listener
        builder.filter(_ -> false);
        assertEquals(EventListener.Result.SUCCESS, listener.run(new Valued(1)));
    }
}
