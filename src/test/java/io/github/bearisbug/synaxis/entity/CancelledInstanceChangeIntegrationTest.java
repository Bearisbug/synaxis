package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.instance.AddEntityToInstanceEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class CancelledInstanceChangeIntegrationTest {

    @Test
    public void cancelledSpawnReturnsAFailedFuture(Env env) {
        var instance = env.createFlatInstance();
        env.process().eventHandler().addListener(AddEntityToInstanceEvent.class, event -> event.setCancelled(true));

        var entity = new Entity(EntityType.ZOMBIE);
        var future = entity.setInstance(instance, new Pos(0, 42, 0));

        assertNotNull(future, "setInstance must never return null");
        assertThrows(CompletionException.class, future::join);
        assertNull(entity.getInstance());
    }

    @Test
    public void cancelledPlayerSpawnKeepsThePlayerInItsInstance(Env env) {
        var origin = env.createFlatInstance();
        var target = env.createFlatInstance();
        var player = env.createPlayer(origin, new Pos(0, 42, 0));
        env.process().eventHandler().addListener(AddEntityToInstanceEvent.class, event -> event.setCancelled(true));
        var spawns = env.trackEvent(PlayerSpawnEvent.class, EventFilter.PLAYER, player);

        player.setInstance(target, new Pos(0, 42, 0)).join();

        assertSame(origin, player.getInstance(), "a cancelled spawn must not half-move the player");
        spawns.assertEmpty();
    }
}
