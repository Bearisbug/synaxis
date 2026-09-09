package dev.gameengine.timer;

public enum ExecutionType {
    /**
     * Schedule tasks to execute at the beginning of the {@link Schedulable} tick
     */
    TICK_START,
    /**
     * Schedule tasks to execute at the end of the {@link Schedulable} tick
     */
    TICK_END
}
