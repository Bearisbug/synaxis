package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TRAINING_DUMMY} of the default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}, state machine
 * §11.6): one zombie without AI 3 blocks east of the spawn, facing it; dead, it is removed 20 ticks later and a new one
 * stands at (3.5, 40, 0.5) 100 ticks after the death, and one that fell below the void line counts as dead.
 */
@EnvTest
class DefaultPlayTrainingDummyIntegrationTest {

    private static final Pos DUMMY = new Pos(3.5, 40, 0.5, 90, 0);

    @Test
    void theDummyStandsBesideTheSpawnFacingIt(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.TRAINING_DUMMY), instance);

        final List<LivingEntity> dummies = PlayFixture.zombies(instance);

        assertEquals(1, dummies.size(), "zombies in the world: " + dummies);
        final LivingEntity dummy = dummies.getFirst();
        assertSame(LivingEntity.class, dummy.getClass(), "a plain living entity, which has no AI");
        assertEquals(DUMMY, dummy.getPosition());
        assertEquals(20f, dummy.getHealth());
        assertFalse(dummy.isInvulnerable());
    }

    @Test
    void aDeadDummyIsRemovedAfter20TicksAndReplacedAfter100(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.TRAINING_DUMMY), instance);
        final LivingEntity dummy = PlayFixture.zombies(instance).getFirst();
        // Knocked back away from where it stood
        dummy.acquirable().sync(owned -> owned.teleport(new Pos(6.0, 40, 0.5, 90, 0)).join());

        dummy.acquirable().sync(LivingEntity::kill);

        assertReplaced(env, instance, dummy);
    }

    @Test
    void aDummyBelowTheVoidLineDiesAndIsReplaced(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.TRAINING_DUMMY), instance);
        final LivingEntity dummy = PlayFixture.zombies(instance).getFirst();
        final int voidLine = instance.getCachedDimensionType().minY() - 64;
        dummy.acquirable().sync(owned -> owned.teleport(new Pos(3.5, voidLine - 1, 0.5)).join());

        env.tick();

        assertTrue(dummy.isDead(), "the dummy below the void line, at " + dummy.getPosition());
        assertReplaced(env, instance, dummy);
    }

    @Test
    void theDummyNeedsTheCoreWorld(Env env) {
        assertThrows(IllegalArgumentException.class,
                () -> PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.TRAINING_DUMMY), null));
    }

    /**
     * From the tick the dummy died on: still there for 19 more ticks, removed on the 20th, none until the 99th, and a
     * new one on the 100th.
     */
    private static void assertReplaced(Env env, Instance instance, LivingEntity dead) {
        for (int tick = 1; tick < 20; tick++) env.tick();
        assertTrue(PlayFixture.present(dead), "removed before its 20th tick dead");
        assertEquals(List.of(dead), PlayFixture.zombies(instance));
        env.tick();
        assertFalse(PlayFixture.present(dead), "still there on its 20th tick dead");
        assertEquals(List.of(), PlayFixture.zombies(instance), "zombies 20 ticks after the death");
        for (int tick = 21; tick < 100; tick++) env.tick();
        assertEquals(List.of(), PlayFixture.zombies(instance), "zombies 99 ticks after the death");
        env.tick();
        final List<LivingEntity> dummies = PlayFixture.zombies(instance);
        assertEquals(1, dummies.size(), "zombies 100 ticks after the death: " + dummies);
        final LivingEntity respawned = dummies.getFirst();
        assertNotSame(dead, respawned);
        assertEquals(DUMMY, respawned.getPosition());
        assertEquals(20f, respawned.getHealth());
        assertFalse(respawned.isDead());
    }
}
