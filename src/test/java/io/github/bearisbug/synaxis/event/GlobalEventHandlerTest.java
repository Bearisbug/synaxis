package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.exception.ExceptionManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class GlobalEventHandlerTest {

    @Test
    public void theGlobalHandlerIsARootNamedGlobalAcceptingEveryEvent() {
        var handler = new GlobalEventHandler(new ExceptionManager());
        assertEquals("global", handler.getName());
        assertSame(Event.class, handler.getEventType());
        assertNull(handler.getParent());
    }

    @Test
    public void theGlobalHandlerRoutesToItsExceptionManager() {
        List<Throwable> caught = new ArrayList<>();
        var manager = new ExceptionManager();
        manager.setExceptionHandler(caught::add);
        var handler = new GlobalEventHandler(manager);
        handler.addListener(EventNodeTest.EventTest.class, _ -> {
            throw new IllegalStateException("boom");
        });

        handler.call(new EventNodeTest.EventTest());
        assertEquals(1, caught.size());
        assertEquals("boom", caught.getFirst().getMessage());
    }

    @Test
    public void aProcessExposesTheSameHandlerEveryTime() {
        var process = ServerProcess.of();
        assertSame(process.eventHandler(), process.eventHandler());

        AtomicInteger calls = new AtomicInteger();
        process.eventHandler().addListener(EventNodeTest.EventTest.class, _ -> calls.incrementAndGet());
        process.eventHandler().call(new EventNodeTest.EventTest());
        assertEquals(1, calls.get());
    }
}
