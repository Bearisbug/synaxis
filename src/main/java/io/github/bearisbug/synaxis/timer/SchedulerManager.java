package io.github.bearisbug.synaxis.timer;

import io.github.bearisbug.synaxis.exception.ExceptionManager;
import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import org.jctools.queues.MessagePassingQueue;

import java.util.function.Supplier;

public final class SchedulerManager implements Scheduler {
    private final Scheduler scheduler;
    private final ExceptionManager exception;

    public SchedulerManager(ExceptionManager exception) {
        this.scheduler = Scheduler.newScheduler(exception::handleException);
        this.exception = exception;
    }

    private final MessagePassingQueue<Runnable> shutdownTasks = ConcurrentMessageQueues.mpscUnboundedArrayQueue(1024);
    @Override
    public void process() {
        this.scheduler.process();
    }

    @Override
    public void processTick() {
        this.scheduler.processTick();
    }

    @Override
    public void processTickEnd() {
        this.scheduler.processTickEnd();
    }

    @Override
    public Task submitTask(Supplier<TaskSchedule> task,
                                    ExecutionType executionType) {
        return scheduler.submitTask(task, executionType);
    }

    /**
     * Runs the shutdown tasks, each once, in the order they were registered. A task that throws is
     * reported to the {@link ExceptionManager} and the next one runs all the same. The process runs
     * them once, in the {@code plugins_disabled} phase of its shutdown sequence
     * ({@code docs/DESIGN-PROD.md} §11.4): on the tick scheduler thread between two ticks, or on the
     * shutdown thread when the tick scheduler does not pick them up within 1 s or the watchdog
     * judged the tick dead. This only runs the tasks, the scheduler goes on taking and running others.
     */
    public void shutdown() {
        Runnable task;
        while ((task = this.shutdownTasks.relaxedPoll()) != null) {
            try {
                task.run();
            } catch (Throwable throwable) {
                exception.handleException(throwable);
            }
        }
    }

    /**
     * Registers a task for {@link #shutdown()}. A task registered once the process ran them is never
     * run.
     *
     * @param runnable the task
     */
    public void buildShutdownTask(Runnable runnable) {
        this.shutdownTasks.relaxedOffer(runnable);
    }
}
