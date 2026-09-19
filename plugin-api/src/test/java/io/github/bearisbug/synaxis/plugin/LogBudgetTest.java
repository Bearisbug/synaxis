package io.github.bearisbug.synaxis.plugin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>
 * The appender is attached here rather than through a shared helper because this module has exactly
 * one class that writes a log line, and the assertions are about that one class's output.
 */
class LogBudgetTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogBudgetTest.class);

    private static final int FLOOD = 10_000;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        ((ch.qos.logback.classic.Logger) LOGGER).addAppender(appender);
    }

    @AfterEach
    void detach() {
        ((ch.qos.logback.classic.Logger) LOGGER).detachAppender(appender);
        appender.stop();
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private List<String> messagesAt(Level level) {
        return eventsAt(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void whatOneIntervalSwallowsIsCountedByTheNextLine() {
        final LogBudget budget = new LogBudget(LOGGER, "payloads");

        // The first occurrence is the one that gets written, draining the pending counter
        budget.warn("Dropped a payload: channel={}", "zz:game");
        assertEquals(0, budget.suppressed.get(), "the first occurrence was not reported");

        for (int i = 0; i < FLOOD; i++) budget.warn("Dropped a payload: channel={}", "zz:game");
        assertEquals(FLOOD, budget.suppressed.get(), "a line per occurrence is the thing being fixed");
        assertEquals(List.of("Dropped a payload: channel=zz:game (1 payloads since the last report)"),
                messagesAt(Level.WARN));

        // The next interval, without waiting one out
        budget.lastReport.set(System.nanoTime() - LogBudget.REPORT_INTERVAL);
        budget.warn("Dropped a payload: channel={}", "zz:game");

        final List<String> warnings = messagesAt(Level.WARN);
        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals("Dropped a payload: channel=zz:game (" + (FLOOD + 1) + " payloads since the last report)",
                warnings.get(1));
    }

    @Test
    void theReportedFailureKeepsItsStack() {
        final LogBudget budget = new LogBudget(LOGGER, "failures");
        final IllegalStateException failure = new IllegalStateException("probe failure");

        budget.error(failure, "Plugin channel handler failed: plugin_id={}", "probe");
        for (int i = 0; i < FLOOD; i++) {
            budget.error(failure, "Plugin channel handler failed: plugin_id={}", "probe");

            final List<ILoggingEvent> errors = eventsAt(Level.ERROR);
            assertEquals(1, errors.size(), "a stack per occurrence costs more than a line per occurrence");
            assertEquals("Plugin channel handler failed: plugin_id=probe (1 failures since the last report)",
                    errors.getFirst().getFormattedMessage());
            // The throwable stays the throwable of the line, it does not become an argument of it
            assertNotNull(errors.getFirst().getThrowableProxy(), "the report lost the stack it was written for");
            assertTrue(errors.getFirst().getThrowableProxy().getMessage().contains("probe failure"));
        }
    }
}
