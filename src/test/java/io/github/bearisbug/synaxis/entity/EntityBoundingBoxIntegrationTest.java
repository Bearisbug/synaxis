package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.collision.BoundingBox;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class EntityBoundingBoxIntegrationTest {
    @Test
    public void pose(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        // Bounding box should be from the registry
        assertEquals(player.getEntityType().boundingBox(), player.getBoundingBox());
        player.setPose(EntityPose.STANDING);
        assertEquals(player.getEntityType().boundingBox(), player.getBoundingBox());

        player.setPose(EntityPose.SLEEPING);
        assertEquals(new BoundingBox(0.2, 0.2, 0.2), player.getBoundingBox());

        player.setPose(EntityPose.SNEAKING);
        assertEquals(new BoundingBox(0.6, 1.5, 0.6), player.getBoundingBox());

        player.setPose(EntityPose.FALL_FLYING);
        assertEquals(new BoundingBox(0.6, 0.6, 0.6), player.getBoundingBox());
    }

    @Test
    public void eyeHeight(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        assertEquals(1.62, player.getEyeHeight());

        player.setPose(EntityPose.SLEEPING);
        assertEquals(0.2, player.getEyeHeight());

        player.setPose(EntityPose.SNEAKING);
        assertEquals(1.27, player.getEyeHeight());

        player.setPose(EntityPose.FALL_FLYING);
        assertEquals(0.4, player.getEyeHeight());
    }

    @Test
    public void pickupItem(Env env) {
        final var instance = env.createFlatInstance();
        final var listener = env.listen(PickupItemEvent.class);
        final var spawnPos = new Pos(0, 42, 0);
        final var entity = new LivingEntity(EntityType.ZOMBIE);
        entity.setCanPickupItem(true);
        entity.setInstance(instance, spawnPos).join();

        // 0 is fine here, it's just a delta
        long time = 0L;

        dropItem(instance, spawnPos);
        listener.followup();
        entity.update(time += 1_000L);

        dropItem(instance, spawnPos.sub(.5));
        listener.followup();
        entity.update(time + 1_000L);
    }

    private static void dropItem(final Instance instance, final Pos position) {
        final var entity = new ItemEntity(ItemStack.of(Material.STONE));
        entity.hasPhysics = false;
        entity.setNoGravity(true);
        entity.setInstance(instance, position).join();
    }
}
