package io.github.bearisbug.synaxis.game.client.farview;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** The lines the far view logs to {@code synaxis} while it is open, as {@code LEVEL message}. */
final class LogLines implements AutoCloseable {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LogManager.getLogger("synaxis");
    private final AbstractAppender appender = new AbstractAppender("far-view-test-" + UUID.randomUUID(), null, null, true,
            Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
        }
    };

    LogLines() {
        appender.start();
        logger.addAppender(appender);
    }

    /** @return the WARN lines so far */
    List<String> warnings() {
        return lines.stream().filter(line -> line.startsWith("WARN ")).toList();
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        appender.stop();
    }
}
