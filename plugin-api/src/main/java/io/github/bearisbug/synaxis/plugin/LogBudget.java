package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One line per {@link #REPORT_INTERVAL}, carrying the number of occurrences it stands for
 * ({@code docs/DESIGN.md} §15, {@code docs/ENGINE.md} §8).
 * <p>
 * Same shape as {@code PacketReading#reportUnreadBytes} and
 * {@code EntityTrackerImpl#reportUnregisteredMove} in the engine: one counter for the occurrences
 * not written yet, one for the moment of the last line, and the thread that wins the compare and
 * set is the one that writes. The count goes into the line that does get written, so the volume
 * stays visible even though it is no longer printed.
 * <p>
 * It is an object here rather than two fields per call site because holding it makes the budget the
 * only way in: the per module log rules of {@code docs/ENGINE.md} §8 forbid the classes on an
 * inbound payload path from calling {@link Logger} directly at all, so a line added next to a
 * budgeted one goes red instead of quietly becoming the unbounded one.
 * <p>
 * It sits in the stable surface module rather than in the host because the host is not the only one
 * on a client driven path: a plugin owns the channel its game messages arrive on, and the client
 * picks how many of them to send. It carries no compatibility promise all the same — the bound is
 * the rule, this class is only the shape the repository writes it in.
 * <p>
 * What a budget costs in diagnostics: inside one interval only the first throwable keeps its stack.
 * The granularity is chosen so that this stays bounded by what the server registered rather than by
 * what a client sends — one budget per channel registration and one per event listener, so a
 * plugin that throws on every payload cannot hide another plugin's failure, while everything a
 * client can multiply (channel ids it invents, message ids it invents) shares a single budget. The
 * event ledger has a single budget for the whole file: one unwritable directory is one fact,
 * whoever was writing.
 */
@ApiStatus.Internal
public final class LogBudget {

    static final long REPORT_INTERVAL = TimeUnit.SECONDS.toNanos(10);

    private final Logger logger;
    private final String noun;

    /** Occurrences not written to the log yet, drained by the line that reports them. */
    final AtomicLong suppressed = new AtomicLong();

    final AtomicLong lastReport = new AtomicLong(System.nanoTime() - REPORT_INTERVAL);

    /**
     * @param logger the logger of the class that reports, so the line keeps its own logger name
     * @param noun   what is being counted, read as "{@code N <noun> since the last report}"
     */
    public LogBudget(Logger logger, String noun) {
        this.logger = logger;
        this.noun = noun;
    }

    /**
     * Writes one WARN, or counts this occurrence towards the next one.
     *
     * @param format    the message pattern, without the count this adds at its end
     * @param arguments the arguments of that pattern
     */
    public void warn(String format, Object... arguments) {
        final long occurrences = claim();
        if (occurrences < 0) return;
        logger.warn(format + countPattern(), appended(arguments, occurrences));
    }

    /**
     * Writes one WARN with its stack, or counts this occurrence towards the next one.
     *
     * @param throwable the throwable to write the stack of, kept last so that SLF4J still takes it
     *                  as the throwable of the line
     * @param format    the message pattern, without the count this adds at its end
     * @param arguments the arguments of that pattern
     */
    public void warn(Throwable throwable, String format, Object... arguments) {
        final long occurrences = claim();
        if (occurrences < 0) return;
        logger.warn(format + countPattern(), appended(arguments, occurrences, throwable));
    }

    /**
     * Writes one ERROR with its stack, or counts this occurrence towards the next one.
     *
     * @param throwable the throwable to write the stack of, kept last so that SLF4J still takes it
     *                  as the throwable of the line
     * @param format    the message pattern, without the count this adds at its end
     * @param arguments the arguments of that pattern
     */
    public void error(Throwable throwable, String format, Object... arguments) {
        final long occurrences = claim();
        if (occurrences < 0) return;
        logger.error(format + countPattern(), appended(arguments, occurrences, throwable));
    }

    /**
     * @return the number of occurrences the caller has to report, or {@code -1} to stay quiet
     */
    private long claim() {
        suppressed.incrementAndGet();
        final long now = System.nanoTime();
        final long last = lastReport.get();
        if (now - last < REPORT_INTERVAL) return -1;
        if (!lastReport.compareAndSet(last, now)) return -1;
        return suppressed.getAndSet(0);
    }

    private String countPattern() {
        return " ({} " + noun + " since the last report)";
    }

    private static Object[] appended(Object[] arguments, Object... tail) {
        final Object[] all = Arrays.copyOf(arguments, arguments.length + tail.length);
        System.arraycopy(tail, 0, all, arguments.length, tail.length);
        return all;
    }
}
