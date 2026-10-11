package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.entity.EntityDeathEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * The training dummy of the core world ({@code docs/DESIGN-PROD.md} §11.6, {@code ENT-TrainingDummy}): a zombie with
 * no AI beside the spawn, which takes melee hits and knockback. Dead, it is removed 20 ticks later, which leaves the
 * death animation the time {@code EntityCreature} gives it, and a new one stands at {@link #SPAWN} 100 ticks after the
 * death; one that fell below the void line counts as dead. There is at most one in the world, and it is not saved.
 */
final class TrainingDummy {

    /**
     * Three blocks east of the core world's spawn, facing it.
     */
    static final Pos SPAWN = new Pos(3.5, 40, 0.5, 90, 0);

    private static final int REMOVE_AFTER_TICKS = 20;

    private static final int RESPAWN_AFTER_TICKS = 100;

    private final ServerProcess process;
    private final Instance world;
    /**
     * The dummy standing or dying, written when one spawns, on the startup thread and then the tick scheduler thread,
     * and read by the death listener on the dummy's tick thread.
     */
    private volatile @Nullable LivingEntity current;

    TrainingDummy(ServerProcess process, Instance world) {
        this.process = process;
        this.world = world;
    }

    /**
     * Listens for the dummy's death and spawns the first one. On the startup thread, before the port is bound.
     *
     * @param hostNode the host node
     */
    void attach(EventNode<Event> hostNode) {
        hostNode.addListener(EntityDeathEvent.class, this::died);
        spawn();
    }

    /**
     * The core world never unloads a chunk, so the chunk of {@link #SPAWN} is loaded once the first dummy stands in it
     * and a later spawn does not wait.
     */
    private void spawn() {
        final LivingEntity dummy = new LivingEntity(EntityType.ZOMBIE);
        // On its own scheduler, which runs on the tick thread that owns it, as Entity#remove and #kill require
        dummy.scheduler().buildTask(() -> fallOut(dummy)).repeat(TaskSchedule.nextTick()).schedule();
        this.current = dummy;
        dummy.setInstance(world, SPAWN).join();
    }

    private static void fallOut(LivingEntity dummy) {
        final Instance instance = dummy.getInstance();
        if (!dummy.isDead() && instance != null && instance.isInVoid(dummy.getPosition())) dummy.kill();
    }

    /**
     * On the thread the dummy died on, which owns it: the tick thread of the melee hit that killed it, or its own for
     * {@link #fallOut}.
     */
    private void died(EntityDeathEvent event) {
        if (event.getEntity() != current) return;
        event.getEntity().scheduleRemove(REMOVE_AFTER_TICKS, TimeUnit.SERVER_TICK);
        process.scheduler().buildTask(this::spawn).delay(TaskSchedule.tick(RESPAWN_AFTER_TICKS)).schedule();
    }
}
