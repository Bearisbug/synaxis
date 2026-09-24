package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tracking ranges per entity type and per entity, with a vertical bound ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-023}, {@code API-PROD-019}). The process runs with the entity view distance of
 * {@code server-app} (8 chunks), so that the square around the player reaches past 96 blocks.
 */
public class TrackingRangeTest {

    private static final Pos PLAYER = new Pos(0.5, 42, 0.5);

    private Env env;
    private Instance instance;

    @BeforeEach
    void setUp() {
        env = Env.create(settings -> settings.entityViewDistance(8));
        instance = env.createFlatInstance();
    }

    @AfterEach
    void tearDown() {
        env.process().stop();
    }

    private Entity spawn(EntityType type, Pos at) {
        final Entity entity = new Entity(type);
        entity.setNoGravity(true);
        entity.setInstance(instance, at).join();
        return entity;
    }

    private static void teleport(Entity entity, Pos to) {
        entity.acquirable().sync(owned -> owned.teleport(to).join());
    }

    private void refresh() {
        for (int i = 0; i < EntityView.REFRESH_INTERVAL_TICKS; i++) env.tick();
    }

    /** The §19 scenario: an item at 20 blocks across and 12 up is out of its 24 by 8 range, a boss of 96 at 90 is in. */
    @Test
    public void theVerticalBoundHidesAnItemAndAnOverrideShowsABoss() {
        env.process().trackingRanges().setDefault(EntityType.ITEM, 24, 8);
        env.process().trackingRanges().setDefault(EntityType.WITHER, 24, 8);
        final Player player = env.createPlayer(instance, PLAYER);

        final Entity itemAbove = spawn(EntityType.ITEM, PLAYER.add(20, 12, 0));
        final Entity itemLevel = spawn(EntityType.ITEM, PLAYER.add(0, 4, 20));
        final Entity boss = new Entity(EntityType.WITHER);
        boss.setNoGravity(true);
        boss.setTrackingRange(96, 64);
        boss.setInstance(instance, PLAYER.add(90, 0, 0)).join();
        final Entity otherBoss = spawn(EntityType.WITHER, PLAYER.add(0, 0, 90));
        refresh();

        assertFalse(itemAbove.isViewer(player), "12 blocks up is outside a vertical range of 8");
        assertTrue(itemLevel.isViewer(player));
        assertTrue(boss.isViewer(player), "the boss's own range of 96 wins over the type's 24");
        assertFalse(otherBoss.isViewer(player), "a boss without its own range keeps the type's 24");
    }

    /** Within one chunk the tracker reports nothing: the player's own pass has to notice the range. */
    @Test
    public void movingAcrossTheRangeWithinAChunkShowsAndHides() {
        env.process().trackingRanges().setDefault(EntityType.ZOMBIE, 10, 4);
        final Player player = env.createPlayer(instance, PLAYER);
        final Entity zombie = spawn(EntityType.ZOMBIE, new Pos(8.5, 42, 12.5));
        refresh();
        assertFalse(zombie.isViewer(player), "8 by 12 is 14.4 blocks away, outside 10");

        teleport(player, new Pos(8.5, 42, 4.5)); // Same chunk (0, 0)
        refresh();
        assertTrue(zombie.isViewer(player), "8 blocks away");

        teleport(zombie, new Pos(8.5, 47, 12.5));
        refresh();
        assertFalse(zombie.isViewer(player), "5 blocks up is outside a vertical range of 4");
    }

    /** A passenger has no range of its own: it is seen exactly when its vehicle is. */
    @Test
    public void aPassengerFollowsItsVehicleOutOfRange() {
        final Player player = env.createPlayer(instance, PLAYER);
        final Entity vehicle = new Entity(EntityType.HORSE);
        vehicle.setNoGravity(true);
        vehicle.setTrackingRange(5, 5);
        vehicle.setInstance(instance, PLAYER.add(20, 0, 0)).join();
        final Entity passenger = spawn(EntityType.ZOMBIE, PLAYER.add(20, 0, 0));
        vehicle.acquirable().sync(owned -> owned.addPassenger(passenger));
        refresh();
        refresh();
        assertFalse(vehicle.isViewer(player), "20 blocks away, outside 5");
        assertFalse(passenger.isViewer(player), "its vehicle is out of range");

        teleport(player, PLAYER.add(18, 0, 0));
        refresh();
        assertTrue(vehicle.isViewer(player));
        assertTrue(passenger.isViewer(player));

        teleport(player, PLAYER);
        refresh();
        assertFalse(vehicle.isViewer(player));
        assertFalse(passenger.isViewer(player));
    }

    @Test
    public void withoutARangeTheSquareOfTheEntityViewDistanceStays() {
        final Player player = env.createPlayer(instance, PLAYER);
        final Entity far = spawn(EntityType.ZOMBIE, PLAYER.add(130, 60, 0)); // Chunk 8, 60 blocks up
        final Entity outside = spawn(EntityType.ZOMBIE, PLAYER.add(145, 0, 0)); // Chunk 9
        refresh();
        assertTrue(far.isViewer(player));
        assertFalse(outside.isViewer(player));
        assertNull(far.getTrackingRange());
    }

    @Test
    public void aRangeNeverReachesPastTheSquare() {
        final Player player = env.createPlayer(instance, PLAYER);
        final Entity zombie = new Entity(EntityType.ZOMBIE);
        zombie.setNoGravity(true);
        zombie.setTrackingRange(512, 512);
        zombie.setInstance(instance, PLAYER.add(145, 0, 0)).join(); // Chunk 9
        refresh();
        assertFalse(zombie.isViewer(player));
        assertEquals(new TrackingRange(512, 512), zombie.getTrackingRange());
    }

    @Test
    public void aRangeMustBePositive() {
        final Entity zombie = new Entity(EntityType.ZOMBIE);
        assertThrows(IllegalArgumentException.class, () -> zombie.setTrackingRange(0, 8));
        assertThrows(IllegalArgumentException.class, () -> zombie.setTrackingRange(8, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> env.process().trackingRanges().setDefault(EntityType.ITEM, -1, 8));
    }
}
