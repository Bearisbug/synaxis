package io.github.bearisbug.synaxis.exception;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the handling of exceptions.
 * <p>
 * An {@link OutOfMemoryError} is handed to the handler like any other throwable: the process does
 * not try to run its stop sequence on an exhausted heap. Exiting is the JVM's job, through
 * {@code -XX:+ExitOnOutOfMemoryError} on the launch command ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-013}).
 */
public final class ExceptionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionManager.class);

    static final long DEFAULT_REPORT_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    static final AtomicLong defaultUnreported = new AtomicLong();
    static final AtomicLong lastDefaultReport = new AtomicLong(System.nanoTime() - DEFAULT_REPORT_INTERVAL);

    /**
     * The sink when no handler is set: a log line, never the bare standard error stream, and at most
     * one per {@link #DEFAULT_REPORT_INTERVAL} carrying the count of the ones it stands for, the shape
     * of {@code PacketReading#reportUnreadBytes}. A listener that throws on every packet a client
     * sends reports once per packet, so an unbounded sink puts the size of the log in the client's
     * hands. The tick number is the host's to add: the engine has no process wide tick counter.
     */
    private static final ExceptionHandler DEFAULT_HANDLER = new ExceptionHandler() {
        @Override
        public void handleException(Throwable e) {
            handleException(e, null);
        }

        @Override
        public void handleException(Throwable e, @Nullable UUID connectionId) {
            defaultUnreported.incrementAndGet();
            final long now = System.nanoTime();
            final long last = lastDefaultReport.get();
            if (now - last < DEFAULT_REPORT_INTERVAL) return;
            if (!lastDefaultReport.compareAndSet(last, now)) return;
            LOGGER.error("Unhandled exception: connection_id={} ({} exceptions since the last report)",
                    connectionId == null ? "-" : connectionId, defaultUnreported.getAndSet(0), e);
        }
    };

    private @Nullable ExceptionHandler exceptionHandler;

    /**
     * Handles an exception, if no {@link ExceptionHandler} is set it is logged as an ERROR.
     *
     * @param e the occurred exception
     */
    public void handleException(Throwable e) {
        handleException(e, null);
    }

    /**
     * Handles an exception thrown while handling one connection.
     *
     * @param e            the occurred exception
     * @param connectionId the connection it was thrown for, {@code null} when there is none
     */
    public void handleException(Throwable e, @Nullable UUID connectionId) {
        this.getExceptionHandler().handleException(e, connectionId);
    }

    /**
     * Changes the exception handler, to allow custom exception handling.
     *
     * @param exceptionHandler the new {@link ExceptionHandler}, can be set to null to apply the default provider
     */
    public void setExceptionHandler(@Nullable ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Retrieves the current {@link ExceptionHandler}, can be the default one if none is defined.
     *
     * @return the current {@link ExceptionHandler}
     */
    public ExceptionHandler getExceptionHandler() {
        if (this.exceptionHandler == null) this.exceptionHandler = DEFAULT_HANDLER;
        return this.exceptionHandler;
    }
}
