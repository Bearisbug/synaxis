package net.minestom.server.timer;

import it.unimi.dsi.fastutil.HashCommon;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Supplier;

final class TaskImpl implements Task {
    private static final int RUNNING = 0;
    private static final int PARKED = 1;
    /**
     * Unparked while still running, the scheduler must not park it when it comes back.
     */
    private static final int UNPARK_SIGNALLED = 2;

    private static final VarHandle PARK_STATE;

    static {
        try {
            PARK_STATE = MethodHandles.lookup().findVarHandle(TaskImpl.class, "parkState", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private final int id;
    private final Supplier<TaskSchedule> task;
    private final ExecutionType executionType;
    private final SchedulerImpl owner;

    volatile boolean alive;
    volatile int parkState;

    TaskImpl(int id,
             Supplier<TaskSchedule> task,
             ExecutionType executionType,
             SchedulerImpl owner) {
        this.id = id;
        this.task = task;
        this.executionType = executionType;
        this.owner = owner;
        this.alive = true;
    }

    @Override
    public void unpark() {
        this.owner.unparkTask(this);
    }

    /**
     * Consumes the park state on behalf of {@link #unpark()}.
     *
     * @return true if the task was parked and must therefore be queued again by the caller
     */
    boolean tryUnpark() {
        while (true) {
            final int state = parkState;
            if (state == UNPARK_SIGNALLED) return false;
            final int next = state == PARKED ? RUNNING : UNPARK_SIGNALLED;
            if (PARK_STATE.compareAndSet(this, state, next)) return state == PARKED;
        }
    }

    /**
     * Parks the task unless an unpark was signalled while it was still running.
     *
     * @return true if the task is now parked, false if the caller must queue it again
     */
    boolean tryPark() {
        while (true) {
            final int state = parkState;
            if (state == UNPARK_SIGNALLED) {
                if (PARK_STATE.compareAndSet(this, UNPARK_SIGNALLED, RUNNING)) return false;
            } else if (PARK_STATE.compareAndSet(this, state, PARKED)) {
                return true;
            }
        }
    }

    @Override
    public boolean isParked() {
        return parkState == PARKED;
    }

    @Override
    public void cancel() {
        this.alive = false;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public int id() {
        return id;
    }

    public Supplier<TaskSchedule> task() {
        return task;
    }

    @Override
    public ExecutionType executionType() {
        return executionType;
    }

    @Override
    public SchedulerImpl owner() {
        return owner;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (TaskImpl) obj;
        return this.id == that.id;
    }

    @Override
    public int hashCode() {
        return HashCommon.murmurHash3(id);
    }

    @Override
    public String toString() {
        return "TaskImpl[" +
                "id=" + id + ", " +
                "task=" + task + ", " +
                "executionType=" + executionType + ", " +
                "owner=" + owner + ']';
    }

}
