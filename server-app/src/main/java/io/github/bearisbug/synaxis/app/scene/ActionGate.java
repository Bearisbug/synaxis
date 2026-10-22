package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * The two limits the host puts on the actions of one connection before a plugin sees them
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}): at most {@value #PER_SECOND} actions in a whole second
 * window, and a cooldown per action key.
 * <p>
 * One per connection, touched on the player's tick thread and in the serial part of the tick only. Every
 * method takes the time from the caller, a monotonic clock in nanoseconds, so that one action is judged at
 * one instant and a test can drive the clock.
 */
final class ActionGate {

    /**
     * The most actions a connection may send in one whole second window, the action budget of
     * {@code synaxis:play}.
     */
    static final int PER_SECOND = 10;

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private long window = Long.MIN_VALUE;
    private int inWindow;
    private final long[] coolingUntil = new long[S2CScene.MAX_ACTIONS];
    private final boolean[] cooling = new boolean[S2CScene.MAX_ACTIONS];

    /**
     * Counts one action against the window it falls in.
     *
     * @param now the time of the action
     * @return false when the window already holds {@value #PER_SECOND} actions
     */
    boolean admit(long now) {
        final long second = Math.floorDiv(now, SECOND);
        if (second != window) {
            window = second;
            inWindow = 0;
        }
        if (inWindow == PER_SECOND) return false;
        inWindow++;
        return true;
    }

    /**
     * How long an action key still cools down.
     *
     * @param slot the action key
     * @param now  the time of the action
     * @return the time left, 0 when the key does not cool down
     */
    long cooldownLeft(int slot, long now) {
        if (!cooling[slot]) return 0;
        return Math.max(0, coolingUntil[slot] - now);
    }

    /**
     * Starts the cooldown of an action key, replacing the one it had.
     *
     * @param slot     the action key
     * @param now      the time the action was accepted
     * @param duration the cooldown, 0 for none
     */
    void startCooldown(int slot, long now, long duration) {
        cooling[slot] = duration > 0;
        coolingUntil[slot] = now + duration;
    }

    /**
     * Ends every cooldown, which a change of scene does (§11.2).
     */
    void clearCooldowns() {
        Arrays.fill(cooling, false);
    }
}
