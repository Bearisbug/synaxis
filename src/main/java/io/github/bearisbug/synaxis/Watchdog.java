package io.github.bearisbug.synaxis;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The tick watchdog of a process ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-014},
 * {@code API-PROD-013}, state machine §11.5): a platform thread that looks at the tick in flight
 * once a second. A tick running for {@link ServerConfig#watchdogEarlyWarning()} gets the stacks of
 * the thread running it and of every tick thread printed as one WARN, again every
 * {@link ServerConfig#watchdogEarlyWarningEvery()}; one running for {@link ServerConfig#watchdogTimeout()}
 * judges the process dead: the host is told (it writes the crash report) and
 * {@link ServerConfig#watchdogHaltAfter()} later the JVM is halted with {@value #EXIT_CODE}, for
 * systemd or the container to restart it.
 * <p>
 * A process that is judged dead does not come back, even if its tick ends in the meantime: the
 * halt is what the verdict leads to. Between the verdict and the halt the design saves the players
 * ({@code started}, {@code gate_closed} or {@code saving} → {@code saving} of §11.4); that save
 * sequence does not exist yet (wave 3, group 3A), so for now the verdict goes straight to the halt
 * timer.
 * <p>
 * The host starts it and stops it. {@link #stop()} never takes a verdict back: while a tick is in
 * flight the watchdog keeps watching it, so a stop sequence stuck behind a stalled tick still ends
 * in a halt instead of a process that never exits.
 */
@ApiStatus.Internal
public final class Watchdog {

    private static final Logger LOGGER = LoggerFactory.getLogger(Watchdog.class);

    public static final String THREAD_NAME = "Syn-Watchdog";
    /** The exit status of a halted process, {@code EX_SOFTWARE} of {@code sysexits.h}. */
    public static final int EXIT_CODE = 70;
    private static final long PATROL_MILLIS = 1_000;
    /** No tick in flight. {@link System#nanoTime()} can return it, {@link #tickStarted()} moves off it. */
    private static final long IDLE = Long.MIN_VALUE;

    private final long earlyWarning;
    private final long earlyWarningEvery;
    private final long timeout;
    private final long haltAfter;
    private final Supplier<? extends List<? extends Thread>> tickThreads;
    private final LongSupplier nanoClock;
    private final Consumer<String> warnings;
    private final IntConsumer halt;
    private final long patrolMillis;

    // Written by the thread running the tick, read by the watchdog thread
    private volatile long tickStart = IDLE;
    private volatile @Nullable Thread tickingThread;
    /** The start of the tick the last warning was about, so the tick can say it recovered. */
    private final AtomicLong warnedTick = new AtomicLong(IDLE);
    /** Every early warning so far, first and repeated ones alike ({@code synaxis_watchdog_warnings_total}). */
    private final AtomicLong warningCount = new AtomicLong();

    // Only touched by the watchdog thread
    private long lastWarning;
    private long dyingSince = IDLE;
    private boolean halted;

    private volatile boolean stopRequested;
    private @Nullable Thread thread;

    Watchdog(ServerConfig config, Supplier<? extends List<? extends Thread>> tickThreads) {
        this(config, tickThreads, System::nanoTime, text -> LOGGER.warn("{}", text),
                status -> Runtime.getRuntime().halt(status), PATROL_MILLIS);
    }

    /**
     * @param config       the four durations of the watchdog
     * @param tickThreads  the tick threads whose stacks are printed next to the one running the tick
     * @param nanoClock    the clock the durations are measured on
     * @param warnings     where an early warning goes
     * @param halt         what ends the JVM
     * @param patrolMillis how long the watchdog sleeps between two looks at the tick
     */
    Watchdog(ServerConfig config, Supplier<? extends List<? extends Thread>> tickThreads, LongSupplier nanoClock,
             Consumer<String> warnings, IntConsumer halt, long patrolMillis) {
        this.earlyWarning = TimeUnit.MILLISECONDS.toNanos(config.watchdogEarlyWarning());
        this.earlyWarningEvery = TimeUnit.MILLISECONDS.toNanos(config.watchdogEarlyWarningEvery());
        this.timeout = TimeUnit.MILLISECONDS.toNanos(config.watchdogTimeout());
        this.haltAfter = TimeUnit.MILLISECONDS.toNanos(config.watchdogHaltAfter());
        this.tickThreads = tickThreads;
        this.nanoClock = nanoClock;
        this.warnings = warnings;
        this.halt = halt;
        this.patrolMillis = patrolMillis;
    }

    /**
     * Starts watching the ticks ({@code [*] → watching}). A process configured with a
     * {@code synaxis.watchdog.timeout-ms} of 0 has no watchdog: this only says so in the log.
     *
     * @param onDeath called once, on a thread of its own, with how long the tick had been stuck when
     *                the process was judged dead; the host writes its crash report there
     * @throws IllegalStateException if it was already started
     */
    public synchronized void start(LongConsumer onDeath) {
        if (thread != null) throw new IllegalStateException("The watchdog is already started");
        if (timeout == 0) {
            LOGGER.info("Tick watchdog off (synaxis.watchdog.timeout-ms=0)");
            return;
        }
        LOGGER.info("Tick watchdog on: stacks after {} ms and every {} ms after that, judged dead after {} ms, "
                        + "halted with exit code {} {} ms later", millis(earlyWarning), millis(earlyWarningEvery),
                millis(timeout), EXIT_CODE, millis(haltAfter));
        final Thread watchdog = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(() -> run(onDeath));
        this.thread = watchdog;
        watchdog.start();
    }

    /**
     * Stops watching once no tick is in flight. A tick that is in flight is watched to its end,
     * and a process already judged dead is still halted.
     */
    public synchronized void stop() {
        stopRequested = true;
        final Thread watchdog = thread;
        if (watchdog != null) watchdog.interrupt();
    }

    /**
     * The early warnings printed since the process started, first and repeated ones alike.
     *
     * @return the count
     */
    public long warnings() {
        return warningCount.get();
    }

    /**
     * Returns the watchdog thread, {@code null} before {@link #start} and when the watchdog is off.
     */
    synchronized @Nullable Thread thread() {
        return thread;
    }

    private void run(LongConsumer onDeath) {
        while (patrol(onDeath)) {
            try {
                Thread.sleep(patrolMillis);
            } catch (InterruptedException _) {
                // stop(): the next patrol decides whether there is still a tick to watch
            }
        }
    }

    /**
     * One look at the tick in flight.
     *
     * @param onDeath told when the process is judged dead
     * @return whether to keep watching
     */
    boolean patrol(LongConsumer onDeath) {
        if (halted) return false;
        final long now = nanoClock.getAsLong();
        if (dyingSince != IDLE) {
            // `dying`: nothing brings the process back, the halt timer runs out (0 turns the halt off)
            if (haltAfter == 0 || now - dyingSince < haltAfter) return !(haltAfter == 0 && stopRequested);
            LOGGER.error("Tick watchdog: halting the process with exit code {}, {} ms after it was judged dead",
                    EXIT_CODE, millis(now - dyingSince));
            halted = true;
            halt.accept(EXIT_CODE);
            return false;
        }
        final long start = tickStart;
        if (start == IDLE) return !stopRequested;
        final long stuck = now - start;
        if (stuck >= timeout) {
            dyingSince = now;
            LOGGER.error("Tick watchdog: the tick has been running for {} ms, the process is judged dead and is "
                    + "halted in {} ms\n{}", millis(stuck), millis(haltAfter), stacks());
            // On a thread of its own: writing the report must not be able to hold the halt back
            Thread.ofPlatform().name(THREAD_NAME + "-Report").daemon().start(() -> {
                try {
                    onDeath.accept(millis(stuck));
                } catch (RuntimeException e) {
                    LOGGER.error("Tick watchdog: the host could not handle the death of the process", e);
                }
            });
            return true;
        }
        if (earlyWarning == 0 || stuck < earlyWarning) return true;
        final boolean firstWarning = warnedTick.get() != start;
        if (firstWarning || (earlyWarningEvery > 0 && now - lastWarning >= earlyWarningEvery)) {
            // Set before the tick can end and look for it. A tick ending between the read of
            // tickStart above and this line loses its recovery line, nothing else.
            warnedTick.set(start);
            lastWarning = now;
            warningCount.incrementAndGet();
            warnings.accept("Tick watchdog: the tick has been running for " + millis(stuck) + " ms\n" + stacks());
        }
        return true;
    }

    /**
     * Called by the thread running a tick, first thing.
     *
     * @return the start of the tick, handed back to {@link #tickEnded(long)}
     */
    long tickStarted() {
        tickingThread = Thread.currentThread();
        long start = nanoClock.getAsLong();
        if (start == IDLE) start++;
        tickStart = start;
        return start;
    }

    /**
     * Called by the thread running a tick, last thing ({@code warning → watching}).
     *
     * @param start what {@link #tickStarted()} returned
     */
    void tickEnded(long start) {
        tickStart = IDLE;
        if (warnedTick.get() == start && warnedTick.compareAndSet(start, IDLE))
            LOGGER.info("Tick watchdog: the tick recovered, it was stuck for {} ms", millis(nanoClock.getAsLong() - start));
    }

    private String stacks() {
        final List<Thread> threads = new ArrayList<>();
        final Thread ticking = tickingThread;
        if (ticking != null) threads.add(ticking);
        for (Thread tickThread : tickThreads.get()) {
            if (tickThread != ticking) threads.add(tickThread);
        }
        final StringBuilder text = new StringBuilder();
        for (Thread thread : threads) {
            text.append('"').append(thread.getName()).append("\" ")
                    .append(thread.getState().name().toLowerCase(Locale.ROOT)).append('\n');
            for (StackTraceElement frame : thread.getStackTrace()) text.append("\tat ").append(frame).append('\n');
        }
        return text.toString();
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
