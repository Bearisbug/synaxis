package io.github.bearisbug.synaxis.exception;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Where the engine's exceptions end up ({@code docs/DESIGN-PROD.md} {@code API-PROD-013},
 * {@code REQ-PROD-015}).
 */
public class ExceptionManagerTest {

    private record Report(Throwable throwable, @Nullable UUID connectionId) {
    }

    private static ExceptionHandler collecting(List<Report> sink) {
        return new ExceptionHandler() {
            @Override
            public void handleException(Throwable e) {
                sink.add(new Report(e, null));
            }

            @Override
            public void handleException(Throwable e, @Nullable UUID connectionId) {
                sink.add(new Report(e, connectionId));
            }
        };
    }

    @Test
    public void anOutOfMemoryErrorGoesToTheHandlerInsteadOfAStopSequence() {
        final List<Report> reports = new ArrayList<>();
        final ExceptionManager manager = new ExceptionManager();
        manager.setExceptionHandler(collecting(reports));
        final OutOfMemoryError oom = new OutOfMemoryError("test");

        manager.handleException(oom);

        assertEquals(1, reports.size(), "the host decides what an exhausted heap costs, not a stop sequence");
        assertSame(oom, reports.getFirst().throwable());
    }

    @Test
    public void theConnectionReachesTheHandler() {
        final List<Report> reports = new ArrayList<>();
        final ExceptionManager manager = new ExceptionManager();
        manager.setExceptionHandler(collecting(reports));
        final UUID connection = UUID.randomUUID();

        manager.handleException(new IllegalStateException("with"), connection);
        manager.handleException(new IllegalStateException("without"));

        assertEquals(connection, reports.get(0).connectionId());
        assertNull(reports.get(1).connectionId());
    }

    @Test
    public void aHandlerThatIgnoresTheConnectionStillGetsTheException() {
        final List<Throwable> caught = new ArrayList<>();
        final ExceptionManager manager = new ExceptionManager();
        manager.setExceptionHandler(caught::add);
        final IllegalStateException boom = new IllegalStateException("boom");

        manager.handleException(boom, UUID.randomUUID());

        assertEquals(List.of(boom), caught);
    }

    @Test
    @ResourceLock(Resources.SYSTEM_ERR)
    public void theDefaultHandlerDoesNotPrintToStandardError() {
        final String marker = "exception-manager-default-" + UUID.randomUUID();
        final PrintStream original = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            new ExceptionManager().handleException(new IllegalStateException(marker), UUID.randomUUID());
        } finally {
            System.setErr(original);
        }
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains(marker),
                "the default sink is SLF4J, a bare stack trace bypasses the log configuration");
    }
}
