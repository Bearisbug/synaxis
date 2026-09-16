package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.event.instance.InstanceTickEvent;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.UUID;

import static io.github.bearisbug.synaxis.testing.TestUtils.waitUntilCleared;

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

    private static void tmp(InstanceContainer instanceContainer) {
        instanceContainer.eventNode().addListener(InstanceTickEvent.class, _ -> {
            Assertions.assertNotNull(instanceContainer.getUuid());
        });
    }
}
