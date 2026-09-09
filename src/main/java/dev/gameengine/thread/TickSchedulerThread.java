package dev.gameengine.thread;

import dev.gameengine.ServerProcess;
import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;

@ApiStatus.Internal
public final class TickSchedulerThread extends MinestomThread {
    public static final String THREAD_NAME_TICK_SCHEDULER = "Ge-TickScheduler";

    // Windows has an issue with periodically being unable to sleep for < ~16ms at a time
    private static final long SLEEP_THRESHOLD = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows") ? 17 : 2;

    private final ServerProcess serverProcess;
    private final long tickTimeNanos;
    private final int maxTickCatchUp;

    /**
     * @param tickTimeNanos  the wanted duration of a tick
     * @param maxTickCatchUp how many ticks the loop may fall behind before it gives up catching up
     */
    public TickSchedulerThread(ServerProcess serverProcess, long tickTimeNanos, int maxTickCatchUp) {
        super(THREAD_NAME_TICK_SCHEDULER);
        this.serverProcess = serverProcess;
        this.tickTimeNanos = tickTimeNanos;
        this.maxTickCatchUp = maxTickCatchUp;
    }

    @Override
    public void run() {
        long ticks = 0;
        long baseTime = System.nanoTime();
        while (serverProcess.isAlive()) {
            final long tickStart = System.nanoTime();
            try {
                serverProcess.ticker().tick(tickStart);
            } catch (Throwable e) {
                serverProcess.exception().handleException(e);
            }

            ticks++;
            long nextTickTime = baseTime + ticks * tickTimeNanos;
            waitUntilNextTick(nextTickTime);
            // Check if the server can not keep up with the tickrate
            // if it gets too far behind, reset the ticks & baseTime
            // to avoid running too many ticks at once
            if (System.nanoTime() > nextTickTime + tickTimeNanos * maxTickCatchUp) {
                baseTime = System.nanoTime();
                ticks = 0;
            }
        }
    }

    private void waitUntilNextTick(long nextTickTimeNanos) {
        long currentTime;
        while ((currentTime = System.nanoTime()) < nextTickTimeNanos) {
            long remainingTime = nextTickTimeNanos - currentTime;
            // Sleep less the closer we are to the next tick
            long remainingMilliseconds = remainingTime / 1_000_000L;
            if (remainingMilliseconds >= SLEEP_THRESHOLD) {
                sleepThread(remainingMilliseconds / 2);
            }
        }
    }

    private void sleepThread(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            serverProcess.exception().handleException(e);
        }
    }
}
