package io.github.bearisbug.synaxis.timer;

import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import org.jctools.queues.MessagePassingQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class SchedulerImpl implements Scheduler {
    private static final AtomicInteger TASK_COUNTER = new AtomicInteger();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
    });

    private final Consumer<Throwable> exceptionHandler;

    SchedulerImpl(Consumer<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    private final MessagePassingQueue<TaskImpl> tasksToExecute = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    private final MessagePassingQueue<TaskImpl> tickEndTasksToExecute = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Tasks scheduled on a certain tick/tick end
    private final Int2ObjectAVLTreeMap<List<TaskImpl>> tickStartTaskQueue = new Int2ObjectAVLTreeMap<>();
    private final Int2ObjectAVLTreeMap<List<TaskImpl>> tickEndTaskQueue = new Int2ObjectAVLTreeMap<>();
    // Enforces the single consumer contract of the queues above
    private final ReentrantLock consumerLock = new ReentrantLock();

    private int tickState;

    @Override
    public void process() {
        processTickTasks(tickStartTaskQueue, tasksToExecute, 0);
    }

    @Override
    public void processTick() {
        processTickTasks(tickStartTaskQueue, tasksToExecute, 1);
    }

    @Override
    public void processTickEnd() {
        processTickTasks(tickEndTaskQueue, tickEndTasksToExecute, 0);
    }

    private void processTickTasks(Int2ObjectAVLTreeMap<List<TaskImpl>> targetTaskQueue, MessagePassingQueue<TaskImpl> targetTasksToExecute, int tickDelta) {
        synchronized (this) {
            this.tickState += tickDelta;
            int tickToProcess;
            while (!targetTaskQueue.isEmpty() && (tickToProcess = targetTaskQueue.firstIntKey()) <= tickState) {
                final List<TaskImpl> tickScheduledTasks = targetTaskQueue.remove(tickToProcess);
                if (tickScheduledTasks != null) tickScheduledTasks.forEach(targetTasksToExecute::relaxedOffer);
            }
        }
        runTasks(targetTasksToExecute);
    }

    private void runTasks(MessagePassingQueue<TaskImpl> targetQueue) {
        // The queues are single consumer, yet process() is reachable from any thread
        consumerLock.lock();
        try {
            // Only consume what was already queued when entering: a task repeating on
            // TaskSchedule.immediate() re-queues itself here, which would otherwise turn this
            // into an endless loop on the queue implementations whose drain is unbounded.
            int budget = targetQueue.size();
            while (budget-- > 0) {
                final TaskImpl task = targetQueue.relaxedPoll();
                if (task == null) break;
                if (task.isAlive()) handleTask(task);
            }
        } finally {
            consumerLock.unlock();
        }
    }

    @Override
    public Task submitTask(Supplier<TaskSchedule> task,
                                    ExecutionType executionType) {
        final TaskImpl taskRef = new TaskImpl(TASK_COUNTER.getAndIncrement(), task,
                executionType, this);
        handleTask(taskRef);
        return taskRef;
    }

    void unparkTask(TaskImpl task) {
        if (task.tryUnpark()) queueTask(task);
    }

    /**
     * Queues a task for the next processing of its own execution type.
     * <p>
     * Callable from any thread: the delay thread, the thread completing a future, the caller of
     * {@link Task#unpark()}. Both queues are unbounded multi-producer single-consumer queues.
     */
    private void queueTask(TaskImpl task) {
        switch (task.executionType()) {
            case TICK_START -> tasksToExecute.relaxedOffer(task);
            case TICK_END -> tickEndTasksToExecute.relaxedOffer(task);
        }
    }

    private void handleTask(TaskImpl task) {
        // Whatever the task or its schedule throws stays here: out of process(), it would drop the
        // tasks still queued behind this one and skip the rest of the tick
        try {
            final TaskSchedule schedule = Objects.requireNonNull(task.task().get(), "The task returned no schedule");
            switch (schedule) {
                case TaskScheduleImpl.DurationSchedule durationSchedule -> {
                    // Saturated, a duration too long for a long of milliseconds never runs instead of throwing
                    final long delay = TimeUnit.MILLISECONDS.convert(durationSchedule.duration());
                    var _ = SCHEDULER.schedule(() -> queueTask(task), delay, TimeUnit.MILLISECONDS);
                }
                case TaskScheduleImpl.TickSchedule tickSchedule -> {
                    synchronized (this) {
                        // Saturated, the sum would otherwise wrap to a tick already passed
                        final int target = (int) Math.min((long) tickState + tickSchedule.tick(), Integer.MAX_VALUE);
                        var targetTaskQueue = switch (task.executionType()) {
                            case TICK_START -> tickStartTaskQueue;
                            case TICK_END -> tickEndTaskQueue;
                        };
                        targetTaskQueue.computeIfAbsent(target, _ -> new ArrayList<>()).add(task);
                    }
                }
                // Completed either way, the task reads the outcome of the future itself
                case TaskScheduleImpl.FutureSchedule futureSchedule -> {
                    var _ = futureSchedule.future().whenComplete((_, _) -> queueTask(task));
                }
                // An unpark received while the task was still running must not be lost
                case TaskScheduleImpl.Park _ -> {
                    if (!task.tryPark()) queueTask(task);
                }
                case TaskScheduleImpl.Stop _ -> task.cancel();
                case TaskScheduleImpl.Immediate _ -> queueTask(task);
            }
        } catch (Throwable t) {
            exceptionHandler.accept(t);
            task.cancel();
        }
    }

    @Override
    public String toString() {
        return "SchedulerImpl{" +
                "tasksToExecute=" + tasksToExecute +
                ", tickEndTasksToExecute=" + tickEndTasksToExecute +
                ", tickStartTaskQueue=" + tickStartTaskQueue +
                ", tickEndTaskQueue=" + tickEndTaskQueue +
                ", tickState=" + tickState +
                '}';
    }
}
