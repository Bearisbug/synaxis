package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestUtils;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the plugin scheduler keeps of a {@code runOn} task that never runs ({@code docs/DESIGN-PROD.md}
 * REQ-PROD-021, API-PROD-017): its entity is removed or its instance unregistered before it is due, or
 * the plugin cancels it. The plugin's task table must not keep such a task, and through the task's
 * scheduler its target, until the plugin is disabled.
 */
@EnvTest
class PluginSchedulerRunOnIntegrationTest {

    private static final Pos SPAWN = new Pos(0, 42, 0);

    @Test
    void aTaskForAnEntityRemovedBeforeItIsDueLetsTheEntityGo(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final AtomicInteger ran = new AtomicInteger();
        final WeakReference<Entity> entity = removedWithATaskDue(env, scheduler, ran);

        for (int i = 0; i < 10; i++) env.tick();

        assertEquals(0, ran.get(), "a task ran for an entity removed");
        TestUtils.waitUntilCleared(entity);
        scheduler.cancelAll();
    }

    @Test
    void aTaskForAnInstanceUnregisteredBeforeItIsDueLetsTheInstanceGo(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final AtomicInteger ran = new AtomicInteger();
        final WeakReference<Instance> instance = unregisteredWithATaskDue(env, scheduler, ran);

        for (int i = 0; i < 10; i++) env.tick();

        assertEquals(0, ran.get(), "a task ran for an instance unregistered");
        TestUtils.waitUntilCleared(instance);
        scheduler.cancelAll();
    }

    @Test
    void aTaskThePluginCancelledIsNotKept(Env env) {
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl("probe", env.process().scheduler());
        final Entity entity = spawned(env, env.createFlatInstance());
        final AtomicInteger ran = new AtomicInteger();
        final WeakReference<Task> task = cancelled(scheduler, entity, ran);

        // The entity's scheduler drops the cancelled task once it is due
        for (int i = 0; i < 10; i++) env.tick();

        assertEquals(0, ran.get(), "a cancelled task ran");
        TestUtils.waitUntilCleared(task);
        scheduler.cancelAll();
    }

    private static Entity spawned(Env env, Instance instance) {
        final Entity entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, SPAWN).join();
        env.tick();
        return entity;
    }

    /** Only the reference leaves, so that nothing on the test's stack holds the entity. */
    private static WeakReference<Entity> removedWithATaskDue(Env env, PluginSchedulerImpl scheduler, AtomicInteger ran) {
        final Entity entity = spawned(env, env.createFlatInstance());
        var _ = scheduler.runOn(entity, ran::incrementAndGet, TaskSchedule.tick(5));
        entity.acquirable().sync(Entity::remove);
        return new WeakReference<>(entity);
    }

    /** The task reads the instance, as a plugin's would; only the reference leaves. */
    private static WeakReference<Instance> unregisteredWithATaskDue(Env env, PluginSchedulerImpl scheduler,
                                                                    AtomicInteger ran) {
        final Instance instance = env.createFlatInstance();
        var _ = scheduler.runOn(instance, () -> ran.addAndGet(instance.getPlayers().size() + 1), TaskSchedule.tick(5));
        env.destroyInstance(instance);
        return new WeakReference<>(instance);
    }

    private static WeakReference<Task> cancelled(PluginSchedulerImpl scheduler, Entity entity, AtomicInteger ran) {
        final Task task = scheduler.runOn(entity, ran::incrementAndGet, TaskSchedule.tick(5));
        task.cancel();
        return new WeakReference<>(task);
    }
}
