package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.PluginScheduler;
import io.github.bearisbug.synaxis.timer.Schedulable;
import io.github.bearisbug.synaxis.timer.Scheduler;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduler capability of one plugin (REQ-M2-004, REQ-M2-005, {@code docs/DESIGN-PROD.md}
 * REQ-PROD-021): every task is remembered so that disabling the plugin cancels all of them, and
 * wrapped so that an exception it throws is logged against this plugin.
 * <p>
 * A {@link #runAsync} body runs on a virtual thread; its handle is an engine task parked on the
 * body's completion, so it reads alive exactly while the body runs. The engine task cannot tell
 * anyone it was cancelled, so while bodies run a sweeper looks at their handles once per tick and
 * interrupts the body of a cancelled one.
 */
final class PluginSchedulerImpl implements PluginScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginSchedulerImpl.class);

    private final String pluginId;
    private final Scheduler scheduler;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    /**
     * One shot tasks of {@link #runOn} and {@link #runAsync}, dropped once they are over. Held weakly: a
     * {@link #runOn} task whose target is removed or unregistered before it is due, or that the plugin
     * cancels, is never over, and through the target's scheduler it holds the target. As long as such a
     * task can still run, that scheduler holds it, so {@link #cancelAll} still finds it.
     */
    private final Map<Task, Boolean> transientTasks = Collections.synchronizedMap(new WeakHashMap<>());
    /** The running {@link #runAsync} bodies, by their handle. */
    private final Map<Task, Thread> asyncBodies = new ConcurrentHashMap<>();
    private final AtomicReference<Task> sweeper = new AtomicReference<>();
    private volatile boolean closed;

    PluginSchedulerImpl(String pluginId, Scheduler scheduler) {
        this.pluginId = pluginId;
        this.scheduler = scheduler;
    }

    @Override
    public Task scheduleTask(Runnable task, TaskSchedule delay, TaskSchedule repeat) {
        final Task submitted = scheduler.buildTask(guarded(task)).delay(delay).repeat(repeat).schedule();
        tasks.add(submitted);
        return submitted;
    }

    @Override
    public Task runAsync(Runnable task) {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final Task handle = scheduler.buildTask(() -> {
        }).delay(TaskSchedule.future(done)).schedule();
        final Thread body = Thread.ofVirtual().name("plugin-async-" + pluginId).unstarted(() -> {
            try {
                guarded(task).run();
            } finally {
                asyncBodies.remove(handle);
                transientTasks.remove(handle);
                done.complete(null);
            }
        });
        transientTasks.put(handle, Boolean.TRUE);
        asyncBodies.put(handle, body);
        startSweeper();
        body.start();
        // A plugin disabled while this call was running has already been swept, so the body is
        // stopped here instead of being left to run for a plugin that is gone
        if (closed) cancelAll();
        return handle;
    }

    @Override
    public Task runOn(Schedulable target, Runnable task, TaskSchedule delay) {
        final AtomicReference<Task> self = new AtomicReference<>();
        final Runnable body = guarded(task);
        final Task submitted = target.scheduler().buildTask(() -> {
            try {
                body.run();
            } finally {
                final Task ran = self.get();
                if (ran != null) transientTasks.remove(ran);
            }
        }).delay(delay).schedule();
        self.set(submitted);
        if (submitted.isAlive()) transientTasks.put(submitted, Boolean.TRUE);
        return submitted;
    }

    /**
     * Cancels every task this plugin submitted and interrupts every {@link #runAsync} body still
     * running, part of the one shot teardown of REQ-M2-004.
     */
    void cancelAll() {
        closed = true;
        for (Task task : tasks) task.cancel();
        tasks.clear();
        // A copy taken under the map's lock: tick threads drop the tasks that ran meanwhile
        for (Task task : List.copyOf(transientTasks.keySet())) task.cancel();
        transientTasks.clear();
        for (Thread body : asyncBodies.values()) body.interrupt();
        asyncBodies.clear();
        final Task running = sweeper.getAndSet(null);
        if (running != null) running.cancel();
    }

    private void startSweeper() {
        if (sweeper.get() != null) return;
        final Task sweep = scheduler.buildTask(this::sweep).repeat(TaskSchedule.nextTick()).schedule();
        if (!sweeper.compareAndSet(null, sweep)) sweep.cancel();
    }

    private void sweep() {
        for (Map.Entry<Task, Thread> entry : asyncBodies.entrySet()) {
            if (!entry.getKey().isAlive()) entry.getValue().interrupt();
        }
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                LOGGER.error("Plugin task failed: plugin_id={}", pluginId, throwable);
            }
        };
    }
}
