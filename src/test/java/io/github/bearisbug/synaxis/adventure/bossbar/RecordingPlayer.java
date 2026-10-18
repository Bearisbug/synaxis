package io.github.bearisbug.synaxis.adventure.bossbar;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BossBarPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A player that records the boss bar actions queued for it and lets a test step in at two points of showing a bar:
 * when a viewer set compares the player with the viewers already in it, and before an action is queued.
 *
 * <p>A test steps in by starting the competing call on another thread with {@link #aside}, which returns once that
 * call has finished or waits for a monitor. Whatever the boss bar manager holds at that point decides which of the
 * two it is.</p>
 */
final class RecordingPlayer extends Player {
    final List<Class<? extends BossBarPacket.Action>> received = new CopyOnWriteArrayList<>();
    private volatile @Nullable Class<? extends BossBarPacket.Action> armedAction;
    private volatile @Nullable Runnable beforeQueuing;
    private volatile @Nullable Runnable onCompare;

    RecordingPlayer(PlayerConnection playerConnection, GameProfile gameProfile) {
        super(playerConnection, gameProfile);
    }

    /** Runs {@code hook} once, before the first action of type {@code action} is queued for this player. */
    void beforeQueuing(Class<? extends BossBarPacket.Action> action, Runnable hook) {
        this.beforeQueuing = hook;
        this.armedAction = action;
    }

    /** Runs {@code hook} once, the next time this player is compared with another object. */
    void onNextCompare(Runnable hook) {
        this.onCompare = hook;
    }

    @Override
    public void sendPacket(SendablePacket packet) {
        if (SendablePacket.extractServerPacket(ConnectionState.PLAY, packet) instanceof BossBarPacket bossBar) {
            final Class<? extends BossBarPacket.Action> armed = this.armedAction;
            final Runnable hook = this.beforeQueuing;
            if (hook != null && armed != null && armed.isInstance(bossBar.action())) {
                this.beforeQueuing = null;
                hook.run();
            }
            received.add(bossBar.action().getClass());
        }
        super.sendPacket(packet);
    }

    @Override
    public boolean equals(Object other) {
        final Runnable hook = this.onCompare;
        if (hook != null) {
            this.onCompare = null;
            hook.run();
        }
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /** Starts {@code action} on a new thread and returns once it has finished or waits to enter a monitor. */
    static FutureTask<Void> aside(Runnable action) {
        final FutureTask<Void> task = new FutureTask<>(action, null);
        final Thread thread = Thread.ofPlatform().daemon().name("boss-bar-aside").start(task);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) throw new AssertionError("The competing call neither finished nor blocked");
            Thread.onSpinWait();
        }
        return task;
    }

    /** Waits for a call started by {@link #aside} and rethrows what it threw. */
    static void join(@Nullable FutureTask<Void> task) throws ExecutionException, InterruptedException, TimeoutException {
        if (task == null) throw new AssertionError("The hook never ran");
        task.get(10, TimeUnit.SECONDS);
    }
}
