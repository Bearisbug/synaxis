package io.github.bearisbug.synaxis.timer;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Represents a scheduler that will execute tasks with a precision based on its ticking rate.
 * If precision is important, consider using a JDK executor service or any third party library.
 * <p>
 * Tasks run on the thread calling {@link #process()}, {@link #processTick()} or {@link #processTickEnd()}.
 * Only the first call of the supplier given to {@link #submitTask(Supplier, ExecutionType)} runs right away on the
 * submitting thread, to get the first {@link TaskSchedule}; for the shortcuts, it only returns the delay.
 */
public sealed interface Scheduler extends Executor permits SchedulerImpl, SchedulerManager {
    static Scheduler newScheduler(Consumer<Throwable> exceptionHandler) {
        return new SchedulerImpl(exceptionHandler);
    }

    /**
     * Process scheduled tasks based on time to increase scheduling precision.
     * <p>
     * The due tasks run on the calling thread. Tasks are consumed by a single caller at a time, so
     * concurrent calls are safe, but the scheduler of a process is only meant to be processed by the
     * tick scheduler thread: its tasks rely on running in the serial part of the tick.
     */
    void process();

    /**
     * Advances 1 tick, then runs the due {@link ExecutionType#TICK_START} tasks.
     * <p>
     * Concurrent calls are memory safe and the tasks are consumed by a single caller at a time, but each call
     * advances the tick: only one driver should call it, once per tick.
     */
    void processTick();

    /**
     * Runs the {@link ExecutionType#TICK_END} tasks due at the current tick, without advancing it.
     * <p>
     * Concurrent calls are safe.
     */
    void processTickEnd();

    /**
     * Submits a new task with custom scheduling logic.
     * <p>
     * This is the primitive method used by all scheduling shortcuts,
     * {@code task} is immediately executed in the caller thread to retrieve its scheduling state
     * and the task will stay alive as long as {@link TaskSchedule#stop()} is not returned (or {@link Task#cancel()} is called).
     *
     * @param task          the task to be directly executed in the caller thread
     * @param executionType the execution type
     * @return the created task
     */
    Task submitTask(Supplier<TaskSchedule> task, ExecutionType executionType);

    default Task submitTask(Supplier<TaskSchedule> task) {
        return submitTask(task, ExecutionType.TICK_START);
    }

    default Task.Builder buildTask(Runnable task) {
        return new Task.Builder(this, task);
    }

    default Task scheduleTask(Runnable task,
                                       TaskSchedule delay, TaskSchedule repeat,
                                       ExecutionType executionType) {
        return buildTask(task).delay(delay).repeat(repeat).executionType(executionType).schedule();
    }

    default Task scheduleTask(Runnable task, TaskSchedule delay, TaskSchedule repeat) {
        return scheduleTask(task, delay, repeat, ExecutionType.TICK_START);
    }

    default Task scheduleTask(Supplier<TaskSchedule> task, TaskSchedule delay) {
        return new Task.Builder(this, task).delay(delay).schedule();
    }

    /**
     * Schedules a task to run on the next tick: on the next call of {@link #processTick()} for
     * {@link ExecutionType#TICK_START}, on the first {@link #processTickEnd()} after it for {@link ExecutionType#TICK_END}.
     *
     * @param task          the task to run
     * @param executionType the execution type
     * @return the created task
     */
    default Task scheduleNextTick(Runnable task, ExecutionType executionType) {
        return buildTask(task).delay(TaskSchedule.nextTick()).executionType(executionType).schedule();
    }

    /**
     * Schedules a task to run on the next call of {@link #processTick()}, as {@link ExecutionType#TICK_START}.
     *
     * @param task the task to run
     * @return the created task
     */
    default Task scheduleNextTick(Runnable task) {
        return scheduleNextTick(task, ExecutionType.TICK_START);
    }

    /**
     * Schedules a task to run on the next call of {@link #processTickEnd()}. A task submitted after the
     * {@code processTickEnd()} of this tick waits for the next tick.
     *
     * @param task the task to run
     * @return the created task
     */
    default Task scheduleEndOfTick(Runnable task) {
        return scheduleNextProcess(task, ExecutionType.TICK_END);
    }

    /**
     * Schedules a task to run the next time its execution type is processed: for {@link ExecutionType#TICK_START}
     * the next call of {@link #process()} or {@link #processTick()}, possibly still within this tick.
     *
     * @param task          the task to run
     * @param executionType the execution type
     * @return the created task
     */
    default Task scheduleNextProcess(Runnable task, ExecutionType executionType) {
        return buildTask(task).delay(TaskSchedule.immediate()).executionType(executionType).schedule();
    }

    /**
     * Schedules a task to run on the next call of {@link #process()} or {@link #processTick()}, possibly still
     * within this tick, as {@link ExecutionType#TICK_START}.
     *
     * @param task the task to run
     * @return the created task
     */
    default Task scheduleNextProcess(Runnable task) {
        return scheduleNextProcess(task, ExecutionType.TICK_START);
    }

    /**
     * Implementation of {@link Executor}, proxies to {@link #scheduleNextTick(Runnable)}.
     * @param command the task to execute on the next tick
     */
    @Override
    default void execute(Runnable command) {
        scheduleNextTick(command);
    }
}
