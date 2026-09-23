package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.exception.ExceptionManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class EventNodeExceptionTest {

    private static GlobalEventHandler handlerCollecting(List<Throwable> sink) {
        var manager = new ExceptionManager();
        manager.setExceptionHandler(sink::add);
        return new GlobalEventHandler(manager);
    }

    @Test
    public void aThrowingListenerIsReportedToTheProcessOwningTheRoot() {
        List<Throwable> caught = new ArrayList<>();
        var root = handlerCollecting(caught);
        var boom = new IllegalArgumentException("boom");
        root.addListener(EventNodeTest.EventTest.class, _ -> {
            throw boom;
        });

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, caught.size());
        assertSame(boom, caught.getFirst());
    }

    @Test
    public void anExceptionInAChildClimbsUpToTheRootHandler() {
        List<Throwable> caught = new ArrayList<>();
        var root = handlerCollecting(caught);
        var child = EventNode.all("child");
        root.addChild(child);
        child.addListener(EventNodeTest.EventTest.class, _ -> {
            throw new IllegalStateException("deep");
        });

        root.call(new EventNodeTest.EventTest());
        assertEquals(1, caught.size());
        assertEquals("deep", caught.getFirst().getMessage());
    }

    @Test
    public void aThrowingListenerAbortsItsDispatchButNotTheNextCall() {
        List<Throwable> caught = new ArrayList<>();
        var root = handlerCollecting(caught);
        AtomicInteger calls = new AtomicInteger();
        root.addListener(EventNodeTest.EventTest.class, _ -> {
            throw new IllegalStateException("boom");
        });
        root.addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());

        root.call(new EventNodeTest.EventTest());
        assertEquals(0, calls.get(), "The rest of the dispatch is abandoned with the exception");

        root.call(new EventNodeTest.EventTest());
        assertEquals(2, caught.size(), "Every call must report its own failure");
    }

    @Test
    public void aDetachedChildStillReportsThroughItsFormerRootOnlyWhileAttached() {
        List<Throwable> caught = new ArrayList<>();
        var root = handlerCollecting(caught);
        var child = EventNode.all("child");
        child.addListener(EventNodeTest.EventTest.class, _ -> {
            throw new IllegalStateException("boom");
        });
        root.addChild(child);
        child.call(new EventNodeTest.EventTest());
        assertEquals(1, caught.size());

        root.removeChild(child);
        child.call(new EventNodeTest.EventTest());
        assertEquals(1, caught.size(), "A detached subtree has no process to report to anymore");
    }
}
