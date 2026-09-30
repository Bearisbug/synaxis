package io.github.bearisbug.synaxis.thread;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.event.server.ServerTickMonitorEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.monitoring.TickMonitor;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acquisition wait of a tick ({@code docs/DESIGN-PROD.md} {@code API-PROD-016}, audit P3-3), on
 * the grid with 4 tick threads: a wait belongs to the thread that was blocked, only engine threads
 * count one, and the figure of the whole tick is the serial part plus the longest tick thread.
 * <p>
 * Every contention is made deterministic: the holder of a lock keeps it until the waiter shows up
 * in the lock's queue, then for a sleep it measures, which the wait therefore contains. Isolated:
 * the global lock is one per JVM, and a test class running alongside could take it while an
 * acquisition here is meant to find it free.
 */
@Isolated
public class AcquireWaitTest {

    private static final long HOLD_MILLIS = 50;
    private static final long PATIENCE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private ServerProcess process;
    private List<TickThread> threads;
    private final List<TickMonitor> monitors = new CopyOnWriteArrayList<>();
    private final Map<TickThread, BlockingQueue<long[]>> reports = new ConcurrentHashMap<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() {
        process = ServerProcess.of(ServerConfig.builder().insideTest(true)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID).dispatcherThreads(4).build());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        threads = process.dispatcher().threads();
        process.eventHandler().addListener(ServerTickMonitorEvent.class, event -> monitors.add(event.getTickMonitor()));
        for (TickThread thread : threads) {
            final BlockingQueue<long[]> queue = new LinkedBlockingQueue<>();
            reports.put(thread, queue);
            thread.observeTicks((tickNanos, acquireNanos) -> queue.add(new long[]{tickNanos, acquireNanos}));
        }
    }

    @AfterEach
    void stop() {
        process.stop();
    }

    /**
     * Two tick threads sync an entity of a third while that one's tick holds it: each wait is the
     * waiting thread's own, and the tick's figure is the longest of them, not their sum, so it stays
     * within the tick. Before that, five ticks sync an entity whose thread is done with its tick:
     * an acquisition nobody contends records exactly 0.
     */
    @Test
    @Timeout(60)
    public void theTickCarriesTheLongestWaitOfATickThread() throws InterruptedException {
        final InstanceContainer instance = flatInstance();
        final Hooked held = place(instance, 0, 0);      // cell (0, 0), thread id 0
        final Hooked waiter = place(instance, 16, 0);   // cell (1, 0), thread id 3
        final Hooked second = place(instance, 0, 16);   // cell (0, 1), thread id 1
        final Hooked idle = place(instance, 32, 0);     // cell (2, 0), thread id 6 → index 2
        tick();
        final TickThread heldThread = threads.get(0), waiterThread = threads.get(3), secondThread = threads.get(1),
                idleThread = threads.get(2);
        assertEquals(heldThread, held.acquirable().assignedThread());
        assertEquals(waiterThread, waiter.acquirable().assignedThread());
        assertEquals(secondThread, second.acquirable().assignedThread());
        assertEquals(idleThread, idle.acquirable().assignedThread());
        // One chunk each, the entity ticked after its chunk: a thread is done with its lock once its
        // entity has ticked, and never hands it over between two elements after that
        for (TickThread thread : List.of(heldThread, waiterThread, secondThread, idleThread))
            assertEquals(1, thread.entries.size(), thread.getName() + " ticks one chunk");

        // Uncontended: the waiter syncs the idle entity once its thread has released its lock for the tick
        final AtomicLong idleTicks = new AtomicLong();
        final AtomicInteger synced = new AtomicInteger();
        idle.hook = idleTicks::incrementAndGet;
        for (int i = 0; i < 5; i++) {
            final long ticked = idleTicks.get() + 1;
            waiter.hook = () -> {
                if (await(() -> idleTicks.get() >= ticked && !idleThread.lock().isLocked(), "the idle thread's tick"))
                    idle.acquirable().sync(_ -> synced.incrementAndGet());
            };
            final Map<TickThread, long[]> ticks = tick();
            assertEquals(List.of(), failures);
            assertEquals(i + 1, synced.get(), "the waiter synced the idle entity from another tick thread");
            assertEquals(0.0, lastMonitor().getAcquisitionTime(), "nothing waited in tick " + i);
            for (Map.Entry<TickThread, long[]> report : ticks.entrySet())
                assertEquals(0, report.getValue()[1], report.getKey().getName() + " waited for nothing");
        }

        // Contended: the waiter and the second sync the held entity during the held entity's tick
        idle.hook = null;
        final CountDownLatch heldTicking = new CountDownLatch(1);
        final CountDownLatch coming = new CountDownLatch(2);
        final AtomicLong slept = new AtomicLong();
        held.hook = () -> {
            heldTicking.countDown();
            if (!await(() -> coming.getCount() == 0 && heldThread.lock().hasQueuedThreads(), "a thread queued on the held one"))
                return;
            slept.set(sleep());
        };
        final Runnable syncHeld = () -> {
            if (!await(() -> heldTicking.getCount() == 0, "the held entity's tick")) return;
            coming.countDown();
            held.acquirable().sync(_ -> synced.incrementAndGet());
        };
        waiter.hook = syncHeld;
        second.hook = syncHeld;
        final Map<TickThread, long[]> ticks = tick();
        assertEquals(List.of(), failures);
        assertEquals(7, synced.get());

        final long waiterWait = ticks.get(waiterThread)[1], secondWait = ticks.get(secondThread)[1];
        final long longest = Math.max(waiterWait, secondWait);
        assertTrue(longest >= slept.get(), "the thread queued on the held one waited out its sleep of "
                + slept.get() + " ns: " + waiterWait + " and " + secondWait);
        assertTrue(waiterWait <= ticks.get(waiterThread)[0] && secondWait <= ticks.get(secondThread)[0],
                "a wait is part of its thread's tick");
        assertEquals(0, ticks.get(heldThread)[1], "the thread holding the entity waited for nothing");
        assertEquals(0, ticks.get(idleThread)[1]);

        final TickMonitor monitor = lastMonitor();
        assertEquals(longest / 1e6D, monitor.getAcquisitionTime(),
                "the tick's wait is the longest of one tick thread (" + waiterWait + " and " + secondWait + " ns)");
        assertTrue(monitor.getAcquisitionTime() <= monitor.getTickTime(),
                monitor.getAcquisitionTime() + " ms waited within a tick of " + monitor.getTickTime() + " ms");
    }

    /**
     * A thread that is not an engine thread waits for another to release an entity between two
     * ticks: that wait is off the tick path and the next tick does not carry it.
     */
    @Test
    @Timeout(60)
    public void aWaitOffTheTickPathIsNotCounted() throws InterruptedException {
        final Hooked held = place(flatInstance(), 0, 0);
        tick();
        final CountDownLatch holding = new CountDownLatch(1);
        final AtomicLong slept = new AtomicLong();
        final AtomicLong contenderWait = new AtomicLong();
        final Thread contender = Thread.ofPlatform().name("contender").unstarted(() -> {
            try {
                holding.await();
            } catch (InterruptedException _) {
                return;
            }
            final long since = System.nanoTime();
            held.acquirable().sync(_ -> {
            });
            contenderWait.set(System.nanoTime() - since);
        });
        final Thread holder = Thread.ofPlatform().name("holder").start(() -> {
            final Acquired<? extends Entity> acquired = held.acquirable().lock();
            try {
                holding.countDown();
                if (await(() -> AcquirableImpl.GLOBAL_LOCK.hasQueuedThread(contender), "the contender queued"))
                    slept.set(sleep());
            } finally {
                acquired.unlock();
            }
        });
        contender.start();
        holder.join();
        contender.join();
        assertEquals(List.of(), failures);
        assertTrue(contenderWait.get() >= slept.get(), "the contender waited out the holder's sleep of "
                + slept.get() + " ns: " + contenderWait.get());

        final Map<TickThread, long[]> ticks = tick();
        assertEquals(0.0, lastMonitor().getAcquisitionTime(), "the tick after a wait of " + contenderWait.get()
                + " ns on a thread that is not an engine thread");
        assertEquals(0, ticks.get(threads.get(0))[1]);
    }

    /**
     * An entity is held from another thread when its tick thread starts the tick: the tick thread
     * blocks on its own lock, and that is its wait. The tick's JFR event carries it too.
     */
    @Test
    @Timeout(60)
    public void aTickThreadBlockedAtTheStartOfItsTickCountsTheWait(@TempDir Path directory) throws Exception {
        final Hooked held = place(flatInstance(), 0, 0);
        tick();
        final TickThread heldThread = threads.get(0);
        final CountDownLatch holding = new CountDownLatch(1);
        final AtomicLong slept = new AtomicLong();
        final Thread holder = Thread.ofPlatform().name("holder").start(() -> {
            final Acquired<? extends Entity> acquired = held.acquirable().lock();
            try {
                holding.countDown();
                if (await(() -> heldThread.lock().hasQueuedThread(heldThread), "the tick thread queued on its own lock"))
                    slept.set(sleep());
            } finally {
                acquired.unlock();
            }
        });
        holding.await();
        final Path file = directory.resolve("tick.jfr");
        final Map<TickThread, long[]> ticks;
        try (Recording recording = new Recording()) {
            recording.enable(EventsJFR.SERVER_TICK);
            recording.start();
            ticks = tick();
            recording.stop();
            recording.dump(file);
        }
        holder.join();
        assertEquals(List.of(), failures);

        final long wait = ticks.get(heldThread)[1];
        assertTrue(wait >= slept.get(), "the tick thread waited out the holder's sleep of " + slept.get() + " ns: " + wait);
        assertTrue(wait <= ticks.get(heldThread)[0], "the wait is part of the thread's tick");
        final TickMonitor monitor = lastMonitor();
        assertEquals(wait / 1e6D, monitor.getAcquisitionTime());
        assertTrue(monitor.getAcquisitionTime() <= monitor.getTickTime());
        final List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        assertTrue(events.stream().anyMatch(event -> event.getDuration("acquisitionTime").toNanos() == wait),
                "the tick event carries the wait of " + wait + " ns: " + events);
    }

    /**
     * The tick runs on an engine thread, as it does on the tick scheduler, and a task of the serial
     * part syncs an entity another thread holds: that wait is the tick's, the tick threads' none.
     */
    @Test
    @Timeout(60)
    public void theSerialPartCountsOnAnEngineThread() throws InterruptedException {
        final Hooked held = place(flatInstance(), 0, 0);
        tick();
        final AtomicInteger synced = new AtomicInteger();
        process.scheduler().scheduleNextTick(() -> held.acquirable().sync(_ -> synced.incrementAndGet()));
        final EngineThread ticker = new EngineThread("Syn-Test-Ticker") {
            @Override
            public void run() {
                process.ticker().tick(System.nanoTime());
            }
        };
        final CountDownLatch holding = new CountDownLatch(1);
        final AtomicLong slept = new AtomicLong();
        final Thread holder = Thread.ofPlatform().name("holder").start(() -> {
            final Acquired<? extends Entity> acquired = held.acquirable().lock();
            try {
                holding.countDown();
                if (await(() -> AcquirableImpl.GLOBAL_LOCK.hasQueuedThread(ticker), "the ticker queued"))
                    slept.set(sleep());
            } finally {
                acquired.unlock();
            }
        });
        holding.await();
        ticker.start();
        ticker.join();
        holder.join();
        final Map<TickThread, long[]> ticks = collect();
        assertEquals(List.of(), failures);
        assertEquals(1, synced.get());

        assertTrue(ticker.acquireWaitNanos() >= slept.get(), "the ticker waited out the holder's sleep of "
                + slept.get() + " ns: " + ticker.acquireWaitNanos());
        assertEquals(0, ticks.get(threads.get(0))[1], "the tick thread started after the holder let go");
        assertEquals(ticker.acquireWaitNanos() / 1e6D, lastMonitor().getAcquisitionTime());
    }

    private InstanceContainer flatInstance() {
        final InstanceContainer instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        return instance;
    }

    private static Hooked place(InstanceContainer instance, int chunkX, int chunkZ) {
        final Hooked entity = new Hooked();
        entity.setInstance(instance, new Pos(chunkX * 16 + 8, 42, chunkZ * 16 + 8)).join();
        return entity;
    }

    /** Ticks once from this thread, with what each tick thread that ticked reported. */
    private Map<TickThread, long[]> tick() throws InterruptedException {
        process.ticker().tick(System.nanoTime());
        return collect();
    }

    /**
     * What each tick thread that ticked the last time reported. A thread reports right after its
     * tick, which may be after the tick as a whole returned.
     */
    private Map<TickThread, long[]> collect() throws InterruptedException {
        final Map<TickThread, long[]> ticks = new HashMap<>();
        for (TickThread thread : threads) {
            if (thread.entries.isEmpty()) continue;
            final long[] report = reports.get(thread).poll(10, TimeUnit.SECONDS);
            assertNotNull(report, thread.getName() + " reported its tick");
            ticks.put(thread, report);
        }
        return ticks;
    }

    private TickMonitor lastMonitor() {
        return monitors.getLast();
    }

    /** Waits for {@code condition}, recording a failure if it does not come within 10 s. */
    private boolean await(BooleanSupplier condition, String what) {
        final long deadline = System.nanoTime() + PATIENCE_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                failures.add("no " + what + " within 10 s");
                return false;
            }
            LockSupport.parkNanos(100_000);
        }
        return true;
    }

    /** Sleeps {@value #HOLD_MILLIS} ms, returning how long it took. */
    private long sleep() {
        final long since = System.nanoTime();
        try {
            Thread.sleep(HOLD_MILLIS);
        } catch (InterruptedException _) {
            failures.add("interrupted while holding");
        }
        return System.nanoTime() - since;
    }

    private static final class Hooked extends Entity {
        volatile @Nullable Runnable hook;

        Hooked() {
            super(EntityType.ZOMBIE);
        }

        @Override
        public void tick(long time) {
            super.tick(time);
            final Runnable hook = this.hook;
            if (hook != null) hook.run();
        }
    }
}
