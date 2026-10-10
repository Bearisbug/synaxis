package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Collects the log lines one logger emits ({@link #of}), or the lines one thread emits through any
 * logger ({@link #rootOfThisThread}), so that a test can assert on the attribution the host puts in
 * them (REQ-M2-005, REQ-M2-006) or on their absence.
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        this.appender.start();
        this.logger.addAppender(appender);
    }

    private LogCapture(Logger logger, Filter<ILoggingEvent> filter) {
        this.logger = logger;
        this.appender.addFilter(filter);
        this.appender.start();
        this.logger.addAppender(appender);
    }

    static LogCapture of(Class<?> type) {
        return new LogCapture((Logger) LoggerFactory.getLogger(type));
    }

    /**
     * Collects the lines the calling thread emits through any logger, as they reach the root logger.
     * The lines of other threads are turned away before they are kept, so only the calling thread
     * touches the list.
     */
    static LogCapture rootOfThisThread() {
        final String thread = Thread.currentThread().getName();
        return new LogCapture((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME), new Filter<>() {
            @Override
            public FilterReply decide(ILoggingEvent event) {
                return thread.equals(event.getThreadName()) ? FilterReply.NEUTRAL : FilterReply.DENY;
            }
        });
    }

    List<String> messagesAt(Level level) {
        return eventsAt(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    List<ILoggingEvent> eventsAt(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
