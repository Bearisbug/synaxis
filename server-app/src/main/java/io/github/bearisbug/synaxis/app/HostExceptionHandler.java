package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.exception.ExceptionHandler;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Where the host sends what the engine and the JVM could not handle ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-013}): the engine's {@code ExceptionManager} and the default uncaught exception
 * handler of every thread.
 * <p>
 * An exception is one ERROR line with its stack, the tick it happened in and the connection it was
 * thrown for. The engine hands most of these over from a path whose rate a client picks (a listener
 * that throws on a packet throws on every such packet), so the line goes through a
 * {@link LogBudget}, one per exception class: the number of budgets is bounded by the classes the
 * server has, not by anything a client sends, and a flood of one failure does not hide the first
 * line of another.
 * <p>
 * A fatal error — a {@link VirtualMachineError} other than a stack overflow, which a single
 * listener can cause and survive — also writes a crash report, once per process: the first fatal
 * error is the one the report is about, and a process that keeps running after it would otherwise
 * write one per occurrence. Nothing here stops the process. An exhausted heap is meant to end in
 * {@code -XX:+ExitOnOutOfMemoryError} (REQ-PROD-028) before this handler runs; without the flag it
 * still only gets this line and the report, because API-PROD-013 has no stop sequence for an
 * exhausted heap. A report that could not be written leaves its own ERROR line from
 * {@link CrashReports}, and every fatal error after the first is logged with {@code crash_report=-}.
 */
final class HostExceptionHandler implements ExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HostExceptionHandler.class);

    private final CrashReports crashReports;
    private final LongSupplier tick;

    private final Map<Class<?>, LogBudget> budgets = new ConcurrentHashMap<>();
    private final LogBudget fatalBudget = new LogBudget(LOGGER, "fatal errors");
    private final AtomicBoolean crashReported = new AtomicBoolean();

    /**
     * @param crashReports the writer of the fatal branch
     * @param tick         the number of the tick being run, read on whichever thread failed
     */
    HostExceptionHandler(CrashReports crashReports, LongSupplier tick) {
        this.crashReports = crashReports;
        this.tick = tick;
    }

    @Override
    public void handleException(Throwable e) {
        handleException(e, null);
    }

    @Override
    public void handleException(Throwable e, @Nullable UUID connectionId) {
        final Object connection = connectionId == null ? "-" : connectionId;
        if (isFatal(e)) {
            final Path report = crashReported.compareAndSet(false, true)
                    ? crashReports.write("fatal error on thread " + Thread.currentThread().getName() + ": " + e, e)
                    : null;
            fatalBudget.error(e, "Fatal error: tick={} connection_id={} crash_report={}", tick.getAsLong(),
                    connection, report == null ? "-" : report);
            return;
        }
        budgets.computeIfAbsent(e.getClass(), _ -> new LogBudget(LOGGER, e.getClass().getSimpleName()))
                .error(e, "Unhandled exception: tick={} connection_id={}", tick.getAsLong(), connection);
    }

    static boolean isFatal(Throwable e) {
        return e instanceof VirtualMachineError && !(e instanceof StackOverflowError);
    }
}
