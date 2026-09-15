package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Showing and hiding an entity takes the two view mutexes ordered by entity id. Any caller that
 * already holds its own mutex reverses that order against every caller coming from the tracker, which
 * is an AB/BA deadlock between two tick threads. The mutexes must therefore be free on entry.
 */
@EnvTest
public class EntityViewLockOrderIntegrationTest {

    private static final class ProbeEntity extends Entity {
        final AtomicBoolean heldOwnMutex = new AtomicBoolean();

        ProbeEntity() {
            super(EntityType.ZOMBIE);
        }

        @Override
        public void updateNewViewer(Player player) {
            recordMutex();
            super.updateNewViewer(player);
        }

        @Override
        public void updateOldViewer(Player player) {
            recordMutex();
            super.updateOldViewer(player);
        }

        private void recordMutex() {
            if (Thread.holdsLock(viewEngine)) heldOwnMutex.set(true);
        }
    }

    @Test
    public void autoViewableUpdateEntersUnlocked(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var probe = new ProbeEntity();
        probe.setInstance(instance, new Pos(0, 42, 0)).join();
        assertTrue(probe.getViewers().contains(player));

        probe.heldOwnMutex.set(false);
        probe.setAutoViewable(false);
        assertEquals(0, probe.getViewers().size());
        assertFalse(probe.heldOwnMutex.get(), "setAutoViewable(false) hid the entity holding its own mutex");

        probe.heldOwnMutex.set(false);
        probe.setAutoViewable(true);
        assertEquals(1, probe.getViewers().size());
        assertFalse(probe.heldOwnMutex.get(), "setAutoViewable(true) showed the entity holding its own mutex");
    }

    @Test
    public void viewableRuleUpdateEntersUnlocked(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        var probe = new ProbeEntity();
        probe.setInstance(instance, new Pos(0, 42, 0)).join();
        assertTrue(probe.getViewers().contains(player));

        probe.heldOwnMutex.set(false);
        probe.updateViewableRule(_ -> false);
        assertEquals(0, probe.getViewers().size());
        assertFalse(probe.heldOwnMutex.get(), "updateViewableRule hid the entity holding its own mutex");

        probe.heldOwnMutex.set(false);
        probe.updateViewableRule(_ -> true);
        assertEquals(1, probe.getViewers().size());
        assertFalse(probe.heldOwnMutex.get(), "updateViewableRule showed the entity holding its own mutex");
    }
}
