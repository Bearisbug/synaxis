package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Collects the log lines one logger emits, so that a test can assert on the attribution the host
 * puts in them (REQ-M2-005, REQ-M2-006).
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        this.appender.start();
        this.logger.addAppender(appender);
    }

    static LogCapture of(Class<?> type) {
        return new LogCapture((Logger) LoggerFactory.getLogger(type));
    }

    List<String> messagesAt(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    void clear() {
        appender.list.clear();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
