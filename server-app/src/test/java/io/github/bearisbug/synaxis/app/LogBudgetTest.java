package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound itself: what a budget swallows has to stay countable, and the one report an interval
 * does get has to keep the stack that made it worth writing.
 */
class LogBudgetTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogBudgetTest.class);

    private static final int FLOOD = 10_000;

    @Test
    void whatOneIntervalSwallowsIsCountedByTheNextLine() {
        final LogBudget budget = new LogBudget(LOGGER, "payloads");

        try (LogCapture logs = LogCapture.of(LogBudgetTest.class)) {
            // The first occurrence is the one that gets written, draining the pending counter
            budget.warn("Dropped a payload: channel={}", "zz:game");
            assertEquals(0, budget.suppressed.get(), "the first occurrence was not reported");

            for (int i = 0; i < FLOOD; i++) budget.warn("Dropped a payload: channel={}", "zz:game");
            assertEquals(FLOOD, budget.suppressed.get(), "a line per occurrence is the thing being fixed");
            assertEquals(List.of("Dropped a payload: channel=zz:game (1 payloads since the last report)"),
                    logs.messagesAt(Level.WARN));

            // The next interval, without waiting one out
            budget.lastReport.set(System.nanoTime() - LogBudget.REPORT_INTERVAL);
            budget.warn("Dropped a payload: channel={}", "zz:game");

            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(2, warnings.size(), warnings.toString());
            assertEquals("Dropped a payload: channel=zz:game (" + (FLOOD + 1) + " payloads since the last report)",
                    warnings.get(1));
        }
    }

    @Test
    void theReportedFailureKeepsItsStack() {
        final LogBudget budget = new LogBudget(LOGGER, "failures");
        final IllegalStateException failure = new IllegalStateException("probe failure");

        try (LogCapture logs = LogCapture.of(LogBudgetTest.class)) {
            budget.error(failure, "Plugin channel handler failed: plugin_id={}", "probe");
            for (int i = 0; i < FLOOD; i++) {
                budget.error(failure, "Plugin channel handler failed: plugin_id={}", "probe");
            }

            final List<ILoggingEvent> errors = logs.eventsAt(Level.ERROR);
            assertEquals(1, errors.size(), "a stack per occurrence costs more than a line per occurrence");
            assertEquals("Plugin channel handler failed: plugin_id=probe (1 failures since the last report)",
                    errors.getFirst().getFormattedMessage());
            // The throwable stays the throwable of the line, it does not become an argument of it
            assertNotNull(errors.getFirst().getThrowableProxy(), "the report lost the stack it was written for");
            assertTrue(errors.getFirst().getThrowableProxy().getMessage().contains("probe failure"));
        }
    }
}
