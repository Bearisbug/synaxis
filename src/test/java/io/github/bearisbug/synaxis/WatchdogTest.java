package io.github.bearisbug.synaxis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tick watchdog ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-014}, state machine §11.5), on a
 * clock the test moves: the patrols are called by hand, one per simulated second.
 */
public class WatchdogTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    /** A watchdog on a fake clock, with what it printed, reported and halted with. */
    private static final class Rig {
        final AtomicLong clock = new AtomicLong(1_000 * SECOND);
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final BlockingQueue<Long> deaths = new LinkedBlockingQueue<>();
        final List<Integer> halts = new CopyOnWriteArrayList<>();
        final LongConsumer onDeath = deaths::add;
        final Watchdog watchdog;
        final long origin = clock.get();

        Rig(ServerConfig config) {
            this(config, 1_000);
        }

        Rig(ServerConfig config, long patrolMillis) {
            this.watchdog = new Watchdog(config, List::of, clock::get, warnings::add, halts::add, patrolMillis);
        }

        void at(long millis) {
            clock.set(origin + TimeUnit.MILLISECONDS.toNanos(millis));
        }

        /** Patrols once a simulated second from {@code fromMillis} to {@code toMillis}, both included. */
        boolean patrolEverySecond(long fromMillis, long toMillis) {
            boolean keepWatching = true;
            for (long millis = fromMillis; millis <= toMillis; millis += 1_000) {
                at(millis);
                keepWatching = watchdog.patrol(onDeath);
            }
            return keepWatching;
        }
    }

    @Test
    public void aStuckTickIsReportedAtTenSecondsThenEveryFiveAndJudgedDeadAtSixty() throws InterruptedException {
        final Rig rig = new Rig(ServerConfig.defaults());
        rig.at(0);
        rig.watchdog.tickStarted();

        rig.patrolEverySecond(0, 9_000);
        rig.at(9_999);
        rig.watchdog.patrol(rig.onDeath);
        assertEquals(List.of(), rig.warnings, "nothing before 10 s");

        rig.at(10_000);
        rig.watchdog.patrol(rig.onDeath);
        assertEquals(1, rig.warnings.size(), "the first warning at 10 s");
        final String first = rig.warnings.getFirst();
        assertTrue(first.startsWith("Tick watchdog: the tick has been running for 10000 ms"), first);
        assertTrue(first.contains('"' + Thread.currentThread().getName() + '"'), "the thread running the tick: " + first);
        assertTrue(first.contains("aStuckTickIsReportedAtTenSecondsThenEveryFiveAndJudgedDeadAtSixty"),
                "its whole stack: " + first);

        rig.at(14_999);
        rig.watchdog.patrol(rig.onDeath);
        assertEquals(1, rig.warnings.size(), "not before another 5 s");
        rig.patrolEverySecond(15_000, 59_000);
        assertEquals(10, rig.warnings.size(), "10, 15, … 55 s");
        assertTrue(rig.warnings.get(9).startsWith("Tick watchdog: the tick has been running for 55000 ms"));
        assertNull(rig.deaths.poll(), "not dead before 60 s");

        rig.at(60_000);
        assertTrue(rig.watchdog.patrol(rig.onDeath));
        assertEquals(60_000L, rig.deaths.poll(10, TimeUnit.SECONDS), "judged dead at 60 s, the host is told");

        rig.at(74_999);
        assertTrue(rig.watchdog.patrol(rig.onDeath), "still dying 14.999 s after the verdict");
        assertEquals(List.of(), rig.halts, "the halt waits 15 s");
        assertEquals(10, rig.warnings.size(), "no more warnings once dead");

        rig.at(75_000);
        assertFalse(rig.watchdog.patrol(rig.onDeath), "a halted watchdog stops watching");
        assertEquals(List.of(Watchdog.EXIT_CODE), rig.halts);
        assertEquals(70, Watchdog.EXIT_CODE);
        assertNull(rig.deaths.poll(), "the host is told once");
    }

    @Test
    public void aTickThatEndsIsWatchedAfreshAndAVerdictIsNeverTakenBack() throws InterruptedException {
        final Rig rig = new Rig(ServerConfig.defaults());
        rig.at(0);
        final long stuck = rig.watchdog.tickStarted();
        rig.patrolEverySecond(0, 12_000);
        assertEquals(1, rig.warnings.size());
        rig.at(12_500);
        rig.watchdog.tickEnded(stuck);

        // watching again: an idle process and a fresh tick are measured from that tick's start
        rig.patrolEverySecond(13_000, 30_000);
        rig.at(30_000);
        final long fresh = rig.watchdog.tickStarted();
        rig.patrolEverySecond(30_000, 39_000);
        assertEquals(1, rig.warnings.size(), "a new tick has its own 10 s");
        rig.patrolEverySecond(40_000, 89_000);
        assertEquals(11, rig.warnings.size(), "40, 45, … 85 s");
        assertNull(rig.deaths.poll(), "the first tick's 60 s did not carry over");

        rig.at(90_000);
        rig.watchdog.patrol(rig.onDeath);
        assertEquals(60_000L, rig.deaths.poll(10, TimeUnit.SECONDS));
        rig.watchdog.tickEnded(fresh);
        rig.at(105_000);
        assertFalse(rig.watchdog.patrol(rig.onDeath));
        assertEquals(List.of(Watchdog.EXIT_CODE), rig.halts, "dying has no way back to watching");
    }

    @Test
    public void zeroTurnsAStageOff() throws InterruptedException {
        final Rig noWarning = new Rig(ServerConfig.builder().watchdogEarlyWarning(0).build());
        noWarning.at(0);
        noWarning.watchdog.tickStarted();
        noWarning.patrolEverySecond(0, 60_000);
        assertEquals(List.of(), noWarning.warnings);
        assertEquals(60_000L, noWarning.deaths.poll(10, TimeUnit.SECONDS), "judged dead all the same");

        final Rig noRepeat = new Rig(ServerConfig.builder().watchdogEarlyWarningEvery(0).build());
        noRepeat.at(0);
        noRepeat.watchdog.tickStarted();
        noRepeat.patrolEverySecond(0, 59_000);
        assertEquals(1, noRepeat.warnings.size(), "only the first warning");

        final Rig noHalt = new Rig(ServerConfig.builder().watchdogHaltAfter(0).build());
        noHalt.at(0);
        noHalt.watchdog.tickStarted();
        assertTrue(noHalt.patrolEverySecond(0, 3_600_000));
        assertEquals(60_000L, noHalt.deaths.poll(10, TimeUnit.SECONDS));
        assertEquals(List.of(), noHalt.halts, "judged dead, never halted");
        noHalt.watchdog.stop();
        assertFalse(noHalt.watchdog.patrol(noHalt.onDeath), "and stoppable, since nothing is left to do");

        final Rig off = new Rig(ServerConfig.builder().watchdogTimeout(0).build());
        off.watchdog.start(off.onDeath);
        assertNull(off.watchdog.thread(), "no watchdog thread at all");

        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().watchdogHaltAfter(-1).build());
    }

    @Test
    public void stopWatchesTheTickInFlightToItsEnd() throws InterruptedException {
        final Rig idle = new Rig(ServerConfig.defaults(), 10);
        idle.watchdog.start(idle.onDeath);
        final Thread idleThread = idle.watchdog.thread();
        assertNotNull(idleThread);
        assertEquals(Watchdog.THREAD_NAME, idleThread.getName());
        assertTrue(idleThread.isDaemon(), "never what keeps a JVM up");
        assertThrows(IllegalStateException.class, () -> idle.watchdog.start(idle.onDeath));
        idle.watchdog.stop();
        idleThread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(idleThread.isAlive(), "no tick in flight, nothing to wait for");

        final Rig busy = new Rig(ServerConfig.defaults(), 10);
        final CountDownLatch ticking = new CountDownLatch(1);
        final Thread tick = Thread.ofPlatform().start(() -> {
            busy.watchdog.tickStarted();
            ticking.countDown();
        });
        ticking.await();
        tick.join();
        busy.watchdog.start(busy.onDeath);
        final Thread busyThread = busy.watchdog.thread();
        assertNotNull(busyThread);
        busy.watchdog.stop();
        Thread.sleep(200);
        assertTrue(busyThread.isAlive(), "a tick in flight is still watched");
        busy.at(60_000);
        assertEquals(60_000L, busy.deaths.poll(10, TimeUnit.SECONDS));
        busy.at(75_000);
        busyThread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(busyThread.isAlive());
        assertEquals(List.of(Watchdog.EXIT_CODE), busy.halts, "a stop sequence stuck behind a tick still ends");
    }

    /**
     * The engine side of the wiring: every tick of a process goes through its watchdog. Halting is
     * turned off, since this one halts the test JVM for real.
     */
    @Test
    public void aTickOfTheProcessStuckPastTheTimeoutIsJudgedDead() throws InterruptedException {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder()
                .watchdogEarlyWarning(200).watchdogTimeout(1_200).watchdogHaltAfter(0).build());
        final BlockingQueue<Long> deaths = new LinkedBlockingQueue<>();
        final CountDownLatch release = new CountDownLatch(1);
        process.scheduler().scheduleNextTick(() -> {
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        process.watchdog().start(deaths::add);
        final List<Throwable> failures = new ArrayList<>();
        final Thread tick = Thread.ofPlatform().name("stuck-tick").start(() -> {
            try {
                process.ticker().tick(System.nanoTime());
            } catch (Throwable e) {
                failures.add(e);
            }
        });
        try {
            final Long stuck = deaths.poll(10, TimeUnit.SECONDS);
            assertNotNull(stuck, "the watchdog saw the tick of the process");
            assertTrue(stuck >= 1_200, "stuck for " + stuck + " ms");
        } finally {
            release.countDown();
            tick.join();
            process.watchdog().stop();
        }
        assertEquals(List.of(), failures);
        final Thread thread = process.watchdog().thread();
        assertNotNull(thread);
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive());
    }
}
