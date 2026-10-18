package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player registered as the viewer of an entity outside its entity view distance square
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-019}): taken in late by a pass of the player that picked it
 * before it left the square, or by two tracker updates crossing on two tick threads. The tracker no longer
 * reports such a pair, so the registration has to go when the entity or the player leaves the instance, and
 * at the next pass of the player otherwise.
 */
public class EntityViewStaleRegistrationTest {

    private static final Pos CENTER = new Pos(0.5, 42, 0.5);

    private Env env;

    @AfterEach
    void tearDown() {
        env.process().stop();
    }

    private Instance start() {
        env = Env.create(settings -> settings.maxVisibleEntitiesPerPlayer(1));
        return env.createFlatInstance();
    }

    private Pos outsideTheSquare() {
        return CENTER.add((env.process().config().entityViewDistance() + 1) * 16, 0, 0);
    }

    private static Entity spawn(Instance instance, Pos at) {
        final Entity entity = new Entity(EntityType.ZOMBIE);
        entity.setNoGravity(true);
        entity.setInstance(instance, at).join();
        return entity;
    }

    private static boolean registered(Player player, Entity entity) {
        synchronized (player.viewEngine) {
            return player.viewEngine.viewerOption.bitSet.contains(entity.getEntityId());
        }
    }

    @Test
    public void aRegistrationOutsideTheSquareGoesWithTheEntity() {
        final Instance instance = start();
        final var connection = env.createConnection();
        final Player player = connection.connect(instance, CENTER);
        final Entity far = spawn(instance, outsideTheSquare());
        assertFalse(far.isViewer(player));
        far.viewEngine.viewableOption.addition.accept(player);
        assertTrue(far.isViewer(player));

        final var destroys = connection.trackIncoming(DestroyEntitiesPacket.class);
        // No tick: the next pass of the player would drop the registration as well, a process with neither a
        // limit nor a range has no pass and only the removal drops it
        far.remove();

        destroys.assertAnyMatch(packet -> packet.entityIds().contains(far.getEntityId()));
        assertFalse(registered(player, far), "the player still counts an entity that is gone");
        final Entity near = spawn(instance, CENTER.add(3, 0, 0));
        assertTrue(near.isViewer(player), "the place the removed entity held is free again");
    }

    @Test
    public void aRegistrationOutsideTheSquareGoesAtTheNextPass() {
        final Instance instance = start();
        final var connection = env.createConnection();
        final Player player = connection.connect(instance, CENTER);
        final Entity far = spawn(instance, outsideTheSquare());
        far.viewEngine.viewableOption.addition.accept(player);

        final var destroys = connection.trackIncoming(DestroyEntitiesPacket.class);
        for (int i = 0; i < EntityView.REFRESH_INTERVAL_TICKS + 1; i++) env.tick();

        destroys.assertAnyMatch(packet -> packet.entityIds().contains(far.getEntityId()));
        assertFalse(far.isViewer(player));
        assertFalse(registered(player, far));
    }

    @Test
    public void aRegistrationOutsideTheSquareGoesWithThePlayer() {
        final Instance instance = start();
        final Instance other = env.createFlatInstance();
        final var connection = env.createConnection();
        final Player player = connection.connect(instance, CENTER);
        final Entity far = spawn(instance, outsideTheSquare());
        far.viewEngine.viewableOption.addition.accept(player);

        player.setInstance(other, CENTER).join();
        assertFalse(far.isViewer(player), "the entity still lists a player that left");
        player.setInstance(instance, CENTER).join();
        far.teleport(CENTER.add(3, 0, 0)).join();

        assertTrue(far.isViewer(player), "coming near again, the entity has to be shown");
        assertTrue(registered(player, far));
    }
}
