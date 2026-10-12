package io.github.bearisbug.synaxis.app.scene;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limits on the actions of one connection ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}): ten
 * actions per whole second window and a cooldown per action key, on a clock the test sets.
 */
class ActionGateTest {

    private static long seconds(double seconds) {
        return Math.round(seconds * TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void tenActionsFitAWholeSecondWindowAndTheEleventhDoesNot() {
        final ActionGate gate = new ActionGate();
        for (int i = 0; i < 10; i++) assertTrue(gate.admit(seconds(100 + i * 0.09)), "action " + i);

        assertFalse(gate.admit(seconds(100.95)), "the eleventh action of the window");
        assertTrue(gate.admit(seconds(101.0)), "the first action of the next window");
    }

    @Test
    void theWindowIsTheWholeSecondNotTheLastSecond() {
        final ActionGate gate = new ActionGate();
        for (int i = 0; i < 10; i++) assertTrue(gate.admit(seconds(100.95)));
        assertFalse(gate.admit(seconds(100.99)));

        assertTrue(gate.admit(seconds(101.0)), "50 ms later is another window");
    }

    @Test
    void aClockBelowZeroStillCutsWholeSeconds() {
        // System.nanoTime may be negative; -0.95 s and -0.05 s are the same window, 0 is the next
        final ActionGate gate = new ActionGate();
        for (int i = 0; i < 10; i++) assertTrue(gate.admit(seconds(-0.95 + i * 0.09)));
        assertFalse(gate.admit(seconds(-0.01)));

        assertTrue(gate.admit(0));
    }

    @Test
    void aKeyCoolsDownForItsDurationAndTheOtherKeysDoNot() {
        final ActionGate gate = new ActionGate();
        final long start = seconds(100.9);
        gate.startCooldown(0, start, seconds(2));

        assertEquals(seconds(2), gate.cooldownLeft(0, start));
        assertEquals(seconds(0.5), gate.cooldownLeft(0, seconds(102.4)));
        assertEquals(0, gate.cooldownLeft(0, seconds(102.9)));
        assertEquals(0, gate.cooldownLeft(1, start), "another key");
    }

    @Test
    void aSceneChangeEndsEveryCooldownAndAZeroCooldownIsNone() {
        final ActionGate gate = new ActionGate();
        gate.startCooldown(0, seconds(10), seconds(2));
        gate.startCooldown(5, seconds(10), seconds(8));
        gate.clearCooldowns();
        assertEquals(0, gate.cooldownLeft(0, seconds(10)));
        assertEquals(0, gate.cooldownLeft(5, seconds(10)));

        gate.startCooldown(3, seconds(10), seconds(2));
        gate.startCooldown(3, seconds(11), 0);
        assertEquals(0, gate.cooldownLeft(3, seconds(11)), "a zero cooldown replaced a running one");
    }
}
