package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.instance.AddEntityToInstanceEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
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
