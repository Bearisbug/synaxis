package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin scheduler additions of {@code docs/DESIGN-PROD.md} REQ-PROD-021 (API-PROD-017): a
 * blocking body off the tick threads, interrupted when the plugin goes, and a task delivered to the
 * thread that owns an entity.
 */
@EnvTest
class PluginSchedulerAsyncIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void runAsyncIsInterruptedWhenThePluginIsDisabled(Env env) throws InterruptedException {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final Task handle = scheduler.runAsync(() -> {
            thread.set(Thread.currentThread());
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException _) {
                interrupted.set(true);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(thread.get().isVirtual(), "a blocking body belongs on a virtual thread");
        assertTrue(handle.isAlive(), "the handle reads alive while the body runs");

        scheduler.cancelAll();

        thread.get().join(WAIT);
        assertTrue(interrupted.get(), "disabling the plugin interrupts the body");
        assertFalse(thread.get().isAlive());
    }

    @Test
    void cancellingTheHandleInterruptsTheBody(Env env) throws InterruptedException {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final Task handle = scheduler.runAsync(() -> {
            thread.set(Thread.currentThread());
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException _) {
                interrupted.set(true);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        handle.cancel();
        // The sweeper looks at the handles once per tick
        assertTrue(env.tickWhile(() -> thread.get().isAlive(), WAIT));
        assertTrue(interrupted.get());
        scheduler.cancelAll();
    }

    @Test
    void theHandleEndsOnTheTickAfterTheBodyFinishes(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final AtomicInteger ran = new AtomicInteger();
        final Task handle = scheduler.runAsync(ran::incrementAndGet);
        assertTrue(env.tickWhile(handle::isAlive, WAIT));
        assertEquals(1, ran.get());
        scheduler.cancelAll();
    }

    @Test
    void aThrowingBodyIsLoggedAndStillEnds(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final Task handle = scheduler.runAsync(() -> {
            throw new IllegalStateException("probe async failure");
        });
        assertTrue(env.tickWhile(handle::isAlive, WAIT));
        scheduler.cancelAll();
    }

    @Test
    void runOnRunsOnTheTickThreadOfTheEntity(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final Instance instance = env.createFlatInstance();
        final Entity entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick();

        final AtomicReference<Thread> ranOn = new AtomicReference<>();
        final Task task = scheduler.runOn(entity, () -> ranOn.set(Thread.currentThread()), TaskSchedule.immediate());
        assertTrue(env.tickWhile(() -> ranOn.get() == null, WAIT));

        assertInstanceOf(TickThread.class, ranOn.get(), "an entity task runs on a tick thread");
        assertSame(entity.acquirable().assignedThread(), ranOn.get(), "and on the one that owns the entity");
        assertTrue(env.tickWhile(task::isAlive, WAIT), "a one shot task ends after it ran");
        scheduler.cancelAll();
    }

    @Test
    void runOnIsCancelledWithThePlugin(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final Instance instance = env.createFlatInstance();
        final AtomicInteger ran = new AtomicInteger();
        final Task task = scheduler.runOn(instance, ran::incrementAndGet, TaskSchedule.tick(3));

        scheduler.cancelAll();
        for (int i = 0; i < 5; i++) env.tick();

        assertFalse(task.isAlive());
        assertEquals(0, ran.get(), "a task of a disabled plugin never runs");
    }

    /**
     * A task submitted once the plugin is torn down, by a body that kept running past its interrupt, is cancelled as
     * it is submitted: a disabled plugin gets no callback (docs/DESIGN-M2.md §11.2).
     */
    @Test
    void aTaskSubmittedAfterThePluginIsTornDownNeverRuns(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final Instance instance = env.createFlatInstance();
        final AtomicInteger ran = new AtomicInteger();
        scheduler.cancelAll();

        final Task repeating = scheduler.scheduleTask(ran::incrementAndGet, TaskSchedule.nextTick(), TaskSchedule.tick(1));
        final Task delivered = scheduler.runOn(instance, ran::incrementAndGet, TaskSchedule.immediate());
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals(0, ran.get(), "a task of a disabled plugin never runs");
        assertFalse(repeating.isAlive());
        assertFalse(delivered.isAlive());
    }
}
