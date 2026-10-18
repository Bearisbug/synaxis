package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.event.instance.InstanceTickEvent;
import io.github.bearisbug.synaxis.event.instance.InstanceUnregisterEvent;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.bearisbug.synaxis.testing.TestUtils.waitUntilCleared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class InstanceUnregisterIntegrationTest {

    @Test
    public void instanceGC(Env env) {
        var instance = env.createFlatInstance();
        var ref = new WeakReference<>(instance);
        env.process().instance().unregisterInstance(instance);

        //noinspection UnusedAssignment
        instance = null;
        waitUntilCleared(ref);
    }

    @Test
    public void instanceNodeGC(Env env) {
        final class Game {
            final Instance instance;

            Game(Env env) {
                instance = env.process().instance().createInstanceContainer();
                instance.eventNode().addListener(PlayerMoveEvent.class, _ -> System.out.println(instance));
            }
        }
        var game = new Game(env);
        var ref = new WeakReference<>(game);
        env.process().instance().unregisterInstance(game.instance);

        //noinspection UnusedAssignment
        game = null;
        waitUntilCleared(ref);
    }

    @Test
    public void chunkGC(Env env) {
        // Ensure that unregistering an instance does release its chunks
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();
        var ref = new WeakReference<>(chunk);
        instance.unloadChunk(chunk);
        env.process().instance().unregisterInstance(instance);
        env.tick(); // Required to remove the chunk from the thread dispatcher

        //noinspection UnusedAssignment
        chunk = null;
        waitUntilCleared(ref);
    }

    @Test
    public void testGCWithEventsLambda(Env env) {
        var ref = new WeakReference<>(new InstanceContainer(env.process(), UUID.randomUUID(), DimensionType.OVERWORLD));
        env.process().instance().registerInstance(ref.get());

        tmp(ref.get());

        ref.get().tick(0);
        env.process().instance().unregisterInstance(ref.get());

        waitUntilCleared(ref);
    }

    // A listener of the unregister event runs plugin code: holding the instance monitor around it
    // would let a sync on an entity of another tick thread deadlock with that thread setting a block.
    @Test
    public void theUnregisterEventIsCalledWithoutTheInstanceMonitor(Env env) {
        var instance = env.process().instance().createInstanceContainer();
        var calls = new AtomicInteger();
        var monitorHeld = new AtomicBoolean();
        var registeredDuringEvent = new AtomicBoolean();
        env.process().eventHandler().addListener(InstanceUnregisterEvent.class, event -> {
            calls.incrementAndGet();
            monitorHeld.set(Thread.holdsLock(event.getInstance()));
            registeredDuringEvent.set(event.getInstance().isRegistered());
        });

        env.process().instance().unregisterInstance(instance);

        assertEquals(1, calls.get(), "unregister events called");
        assertFalse(monitorHeld.get(), "the unregister event was called holding the instance monitor");
        assertTrue(registeredDuringEvent.get(), "the unregister event came after the instance was unregistered");
        assertFalse(instance.isRegistered());
        assertFalse(env.process().instance().getInstances().contains(instance));
    }

    private static void tmp(InstanceContainer instanceContainer) {
        instanceContainer.eventNode().addListener(InstanceTickEvent.class, _ -> {
            Assertions.assertNotNull(instanceContainer.getUuid());
        });
    }
}
