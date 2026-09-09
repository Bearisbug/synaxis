package dev.gameengine.timer;

import dev.gameengine.MinecraftServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TestScheduler {

    @Test
    public void tickTask() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        Task task = scheduler.scheduleNextTick(() -> result.set(true));
        assertEquals(ExecutionType.TICK_START, task.executionType(), "Tasks default execution type should be tick start");

        assertFalse(result.get(), "Tick task should not be executed after scheduling");
        scheduler.process();
        assertFalse(result.get(), "Tick task should not be executed after process");
        scheduler.processTickEnd();
        assertFalse(result.get(), "Tick task should not be executed after processTickEnd");
        scheduler.processTick();
        assertTrue(result.get(), "Tick task must be executed after tick process");
        assertFalse(task.isAlive(), "Tick task should be cancelled after execution");
    }

    @Test
    public void durationTask() throws InterruptedException {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        scheduler.buildTask(() -> result.set(true))
                .delay(TaskSchedule.seconds(1))
                .schedule();
        Thread.sleep(100);
        scheduler.process();
        assertFalse(result.get(), "900ms remaining");
        Thread.sleep(1200);
        scheduler.process();
        assertTrue(result.get(), "Tick task must be executed after 1 second");
    }

    @Test
    public void immediateTask() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        scheduler.scheduleNextProcess(() -> result.set(true));
        assertFalse(result.get());
        scheduler.processTickEnd();
        assertFalse(result.get(), "processTickEnd should never execute immediate tasks unless it is of type TICK_END");
        scheduler.process();
        assertTrue(result.get());

        result.set(false);
        scheduler.process();
        assertFalse(result.get());
    }

    @Test
    public void cancelTask() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        var task = scheduler.buildTask(() -> result.set(true))
                .schedule();
        assertTrue(task.isAlive(), "Task should still be alive");
        task.cancel();
        assertFalse(task.isAlive(), "Task should not be alive anymore");
        scheduler.process();
        assertFalse(result.get(), "Task should be cancelled");
    }

    @Test
    public void parkTask() {
        Scheduler scheduler = Scheduler.newScheduler();
        // Ignored parked task
        scheduler.buildTask(() -> fail("This parked task should never be executed"))
                .executionType(ExecutionType.TICK_START)
                .delay(TaskSchedule.park())
                .schedule();

        // Unpark task
        AtomicBoolean result = new AtomicBoolean(false);
        var task = scheduler.buildTask(() -> result.set(true))
                .delay(TaskSchedule.park())
                .schedule();
        assertTrue(task.isParked());
        assertFalse(result.get(), "Task hasn't been unparked yet");
        task.unpark();
        assertFalse(task.isParked());
        assertFalse(result.get(), "Tasks must be processed first");
        scheduler.process();
        assertFalse(task.isParked());
        assertTrue(result.get(), "Parked task should be executed");
    }

    @Test
    public void futureTask() {
        Scheduler scheduler = Scheduler.newScheduler();
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicBoolean result = new AtomicBoolean(false);
        scheduler.buildTask(() -> result.set(true))
                .delay(TaskSchedule.future(future))
                .schedule();
        assertFalse(result.get(), "Future is not completed yet");
        future.complete(null);
        assertFalse(result.get(), "Tasks must be processed first");
        scheduler.process();
        assertTrue(result.get(), "Future should be completed");
    }

    @Test
    public void exceptionTask() {
        MinecraftServer.init();
        MinecraftServer.getExceptionManager().setExceptionHandler(Assertions::assertNotNull);
        Scheduler scheduler = Scheduler.newScheduler();
        scheduler.scheduleNextTick(() -> {
            throw new RuntimeException("Test exception");
        });

        // This is a bit of a weird use case. I dont want this test to depend on the order the scheduler executes in
        // so this is a guess that the first one wont be before all 100 of the ones scheduled below.
        // Not great, but should be fine anyway.
        AtomicInteger executed = new AtomicInteger(0);
        for (int i = 0; i < 100; i++) {
            scheduler.scheduleNextTick(executed::incrementAndGet);
        }

        assertDoesNotThrow(scheduler::processTick);
        assertEquals(100, executed.get());
    }

    @Test
    public void scheduleEndOfTick() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        scheduler.scheduleEndOfTick(() -> result.set(true));
        assertFalse(result.get(), "End of tick tasks should not be executed immediately upon submission");
        scheduler.processTick();
        assertFalse(result.get(), "End of tick tasks should not be executed by processTick()");
        scheduler.processTickEnd();
        assertTrue(result.get(), "scheduleEndOfTick(...) tasks should be executed after the next call to processTickEnd()");

        result.set(false);
        scheduler.scheduleEndOfTick(() -> result.set(true));
        scheduler.processTickEnd();
        assertTrue(result.get(), "scheduleEndOfTick(...) tasks should always execute on the very next processTickEnd()");
    }

    @Test
    public void delayedEndOfTick() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        scheduler.buildTask(() -> result.set(true)).delay(TaskSchedule.tick(1))
                .executionType(ExecutionType.TICK_END).schedule();

        scheduler.processTickEnd(); scheduler.processTickEnd();
        assertFalse(result.get(), "processTickEnd() should not increment the scheduler's internal tick counter");
        scheduler.processTick();
        scheduler.processTickEnd();
        assertTrue(result.get(), "processTick() should increment the current tick counter processTickEnd() uses");
    }

    @Test
    public void repeatingEndOfTick() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicInteger result = new AtomicInteger(0);
        Task task = scheduler.scheduleTask(result::getAndIncrement, TaskSchedule.immediate(), TaskSchedule.tick(1), ExecutionType.TICK_END);
        assertEquals(0, result.get(), "TICK_END tasks should not be executed immediately upon submission");
        scheduler.processTickEnd();
        assertEquals(1, result.get(), "processTickEnd() should always execute TaskSchedule.immediate() TICK_END tasks");
        scheduler.processTickEnd();
        assertEquals(1, result.get(), "task should not executed on processTickEnd() again until processTick() is called");
        scheduler.processTick();
        assertEquals(1, result.get(), "processTick() should never execute TICK_END tasks");
        scheduler.processTickEnd();
        assertEquals(2, result.get(), "processTickEnd() should execute this task");

        task.cancel();
        scheduler.processTick();
        scheduler.processTickEnd();
        assertEquals(2, result.get(), "this task should have been cancelled");
    }

    @Test
    public void durationEndOfTick() throws InterruptedException {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        scheduler.buildTask(() -> result.set(true))
                .delay(TaskSchedule.seconds(1))
                .executionType(ExecutionType.TICK_END)
                .schedule();
        Thread.sleep(100);
        scheduler.process();
        scheduler.processTickEnd();
        assertFalse(result.get(), "900ms remaining");
        Thread.sleep(1200);
        scheduler.process();
        assertFalse(result.get(), "process() should never execute TICK_END tasks");
        scheduler.processTickEnd();
        assertTrue(result.get(), "Tick end task must be executed after 1 second");
    }

    @Test
    public void unparkTickEndTask() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicBoolean result = new AtomicBoolean(false);
        Task task = scheduler.buildTask(() -> result.set(true))
                .executionType(ExecutionType.TICK_END)
                .delay(TaskSchedule.park())
                .schedule();
        assertTrue(task.isParked());
        task.unpark();
        scheduler.processTick();
        assertFalse(result.get(), "processTick() should never execute TICK_END tasks");
        scheduler.processTickEnd();
        assertTrue(result.get(), "Unparked TICK_END task should be executed at the end of the tick");
    }

    @Test
    public void unparkWhileTaskIsStillRunning() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicReference<Task> ref = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        Task task = scheduler.submitTask(new Supplier<>() {
            @Override
            public TaskSchedule get() {
                final int run = runs.getAndIncrement();
                if (run == 0) return TaskSchedule.immediate();
                if (run == 1) {
                    // The unpark signal is sent before the scheduler had a chance to record the park
                    ref.get().unpark();
                    return TaskSchedule.park();
                }
                return TaskSchedule.stop();
            }
        });
        ref.set(task);
        assertEquals(1, runs.get(), "submitTask executes the task once in the calling thread");

        scheduler.process();
        scheduler.process();
        assertEquals(3, runs.get(), "the unpark sent while the task was running was lost");
        assertFalse(task.isParked());
    }

    @Test
    public void immediateRepeatRunsOncePerProcess() {
        Scheduler scheduler = Scheduler.newScheduler();
        AtomicInteger counter = new AtomicInteger();
        Task task = scheduler.scheduleTask(counter::incrementAndGet, TaskSchedule.immediate(), TaskSchedule.immediate());
        assertEquals(0, counter.get(), "the delay is consumed first");
        scheduler.process();
        assertEquals(1, counter.get(), "an immediate repeat must not be consumed again by the drain that queued it");
        scheduler.process();
        assertEquals(2, counter.get());
        task.cancel();
    }

    @Test
    @Timeout(120)
    public void concurrentProcess() throws InterruptedException {
        final int taskCount = 512;
        final int threadCount = 4;
        for (int attempt = 0; attempt < 100; attempt++) {
            Scheduler scheduler = Scheduler.newScheduler();
            AtomicInteger runs = new AtomicInteger();
            for (int i = 0; i < taskCount; i++) scheduler.scheduleNextProcess(runs::incrementAndGet);

            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    scheduler.process();
                }, "scheduler-consumer-" + i);
                thread.setDaemon(true);
                thread.start();
                threads.add(thread);
            }
            start.countDown();
            for (Thread thread : threads) thread.join();
            scheduler.process();

            assertEquals(taskCount, runs.get(),
                    "concurrent process() calls dropped or duplicated tasks of the single consumer queue");
        }
    }
}
