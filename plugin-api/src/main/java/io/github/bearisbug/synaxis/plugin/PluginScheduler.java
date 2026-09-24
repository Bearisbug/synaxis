package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.timer.Schedulable;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.jetbrains.annotations.ApiStatus;

/**
 * Scheduler capability of one plugin (API-M2-008, {@code docs/DESIGN-PROD.md} API-PROD-017).
 * <p>
 * Tasks run on the engine scheduler, which is only advanced from inside a tick: a task submitted
 * before the server binds its port stays parked until the tick thread starts.
 * <p>
 * The standard path for anything slow, such as persistence, is {@link #runAsync} for the blocking
 * part and {@link #runOn} to apply its result back on the thread that owns the entity or instance.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginScheduler {

    /**
     * Schedules a task owned by this plugin.
     *
     * @param task   the body; an exception it throws is caught and logged against this plugin
     * @param delay  when to run it the first time
     * @param repeat how to repeat it, {@link TaskSchedule#stop()} for a one shot task
     * @return the created task, also cancelled by the host when the plugin is disabled
     */
    Task scheduleTask(Runnable task, TaskSchedule delay, TaskSchedule repeat);

    /**
     * Runs a blocking body on its own virtual thread, off every tick thread.
     * <p>
     * The body must not touch an entity or an instance: hand the result over with {@link #runOn}.
     * The returned task stays alive while the body runs and ends on the tick after it finishes.
     * Cancelling it, or disabling the plugin, interrupts a body that is still running.
     *
     * @param task the body; an exception it throws is caught and logged against this plugin
     * @return the handle of the body
     * @throws UnsupportedOperationException from a scheduler that is not the host's; the default only
     *                                       keeps a scheduler a plugin wrote for its tests compiling
     */
    default Task runAsync(Runnable task) {
        throw new UnsupportedOperationException("only the host's scheduler runs asynchronous tasks");
    }

    /**
     * Runs a task on the thread that owns the target: an entity's task runs on the tick thread of
     * that entity, an instance's on the tick thread of that instance. This is how an effect on
     * another entity is delivered across tick threads (the thread contract of {@code API-PROD-016}).
     *
     * @param target the entity or instance whose thread runs the task
     * @param task   the body; an exception it throws is caught and logged against this plugin
     * @param delay  when to run it, {@link TaskSchedule#immediate()} for the next time the target ticks
     * @return the created task, also cancelled by the host when the plugin is disabled
     * @throws UnsupportedOperationException from a scheduler that is not the host's, for the same
     *                                       reason as {@link #runAsync}'s
     */
    default Task runOn(Schedulable target, Runnable task, TaskSchedule delay) {
        throw new UnsupportedOperationException("only the host's scheduler delivers tasks to a target");
    }
}
