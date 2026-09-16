package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.PluginScheduler;
import io.github.bearisbug.synaxis.timer.Scheduler;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scheduler capability of one plugin (REQ-M2-004, REQ-M2-005): every task is remembered so that
 * disabling the plugin cancels all of them, and wrapped so that an exception it throws is logged
 * against this plugin.
 */
final class PluginSchedulerImpl implements PluginScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginSchedulerImpl.class);

    private final String pluginId;
    private final Scheduler scheduler;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();

    PluginSchedulerImpl(String pluginId, Scheduler scheduler) {
        this.pluginId = pluginId;
        this.scheduler = scheduler;
    }

    @Override
    public Task scheduleTask(Runnable task, TaskSchedule delay, TaskSchedule repeat) {
        final Task submitted = scheduler.buildTask(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                LOGGER.error("Plugin task failed: plugin_id={}", pluginId, throwable);
            }
        }).delay(delay).repeat(repeat).schedule();
        tasks.add(submitted);
        return submitted;
    }

    /**
     * Cancels every task this plugin submitted, part of the one shot teardown of REQ-M2-004.
     */
    void cancelAll() {
        for (Task task : tasks) task.cancel();
        tasks.clear();
    }
}
