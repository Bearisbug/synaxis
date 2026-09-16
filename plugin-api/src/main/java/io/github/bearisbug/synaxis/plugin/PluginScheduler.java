package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;

/**
 * Scheduler capability of one plugin (API-M2-008).
 * <p>
 * Tasks run on the engine scheduler, which is only advanced from inside a tick: a task submitted
 * before the server binds its port stays parked until the tick thread starts.
 */
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
}
